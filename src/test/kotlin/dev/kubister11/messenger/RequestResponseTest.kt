package dev.kubister11.messenger

import dev.kubister11.messenger.listener.CallbackMessageListener
import dev.kubister11.messenger.message.CallbackResponse
import dev.kubister11.messenger.scope.MessageScope
import dev.kubister11.messenger.serialization.MessengerFory
import dev.kubister11.messenger.support.InMemoryMessagingService
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

data class EchoRequest(val text: String)
data class EchoReply(val text: String)

private class EchoListener(
    private val transform: (EchoRequest) -> CallbackResponse<EchoReply>
) : CallbackMessageListener<EchoRequest, EchoReply>() {
    override fun onMessage(packet: EchoRequest): CallbackResponse<EchoReply> = transform(packet)
}

/**
 * Full request/response round-trips over the in-memory transport. This exercises the whole
 * transport-agnostic path: [MessagingService.request] -> publish -> [CallbackMessageListener] ->
 * [MessagingService.respond] -> [dev.kubister11.messenger.listener.CallbackListener] -> future.
 */
class RequestResponseTest {

    private val fory = MessengerFory.create {
        it.register(EchoRequest::class.java)
        it.register(EchoReply::class.java)
    }

    private val service = InMemoryMessagingService(fory).also { it.connect() }

    @AfterTest
    fun tearDown() = service.close()

    @Test
    fun `request completes with the peer response`() {
        service.registerMessageCallbackListener("echo", EchoListener {
            CallbackResponse.success(EchoReply("re: ${it.text}"))
        })

        val reply = service.request<EchoReply>("echo", EchoRequest("hi")).get(5, TimeUnit.SECONDS)

        assertEquals(EchoReply("re: hi"), reply)
        // Correlation entry cleaned up after completion.
        assertTrue(service.requests.isEmpty())
    }

    @Test
    fun `concurrent requests are correlated to their own responses`() {
        service.registerMessageCallbackListener("echo", EchoListener {
            CallbackResponse.success(EchoReply(it.text.uppercase()))
        })

        val futures = (1..50).map { i ->
            service.request<EchoReply>("echo", EchoRequest("m$i"))
        }
        val replies = futures.mapIndexed { i, f -> f.get(5, TimeUnit.SECONDS).text to "M${i + 1}" }

        replies.forEach { (actual, expected) -> assertEquals(expected, actual) }
        assertTrue(service.requests.isEmpty())
    }

    @Test
    fun `a FUTURE response completes the request when it later resolves`() {
        val gate = CompletableFuture<EchoReply?>()
        service.registerMessageCallbackListener("echo", EchoListener {
            CallbackResponse.future(gate)
        })

        val future = service.request<EchoReply>("echo", EchoRequest("hi"))
        assertFalse(future.isDone)

        gate.complete(EchoReply("late"))
        assertEquals(EchoReply("late"), future.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a SKIP response never completes the request`() {
        service.registerMessageCallbackListener("echo", EchoListener { CallbackResponse.skip() })

        val future = service.request<EchoReply>("echo", EchoRequest("hi"), timeout = Duration.ofMillis(300))

        val error = assertFailsWith<ExecutionException> { future.get(5, TimeUnit.SECONDS) }
        assertTrue(error.cause is TimeoutException)
        assertTrue(service.requests.isEmpty())
    }

    @Test
    fun `request reuses a single callback listener across calls`() {
        service.registerMessageCallbackListener("echo", EchoListener {
            CallbackResponse.success(EchoReply(it.text))
        })

        service.request<EchoReply>("echo", EchoRequest("a")).get(5, TimeUnit.SECONDS)
        service.request<EchoReply>("echo", EchoRequest("b")).get(5, TimeUnit.SECONDS)

        assertTrue(service.isMessageListenerRegistered("echo_callback"))
    }

    @Test
    fun `a publish failure fails the request and cleans up its correlation entry`() {
        service.publishFailure = IllegalStateException("broker down")

        val future = service.request<EchoReply>("echo", EchoRequest("hi"))

        val error = assertFailsWith<ExecutionException> { future.get(5, TimeUnit.SECONDS) }
        assertTrue(error.cause is IllegalStateException)
        assertTrue(service.requests.isEmpty(), "correlation entry leaked after publish failure")
    }

    @Test
    fun `close shuts down the callback executor`() {
        val fresh = InMemoryMessagingService(fory).also { it.connect() }
        fresh.close()
        assertTrue(fresh.callbackExecutor.isShutdown)
    }

    @Test
    fun `request on a not-ready service fails fast`() {
        val fresh = InMemoryMessagingService(fory)
        assertFailsWith<IllegalStateException> { fresh.request<EchoReply>("echo", EchoRequest("x")) }
    }

    @Test
    fun `a continuation blocking on another request does not deadlock delivery`() {
        // The documented reason for callbackExecutor: a completion continuation that itself blocks
        // on another request must not run on the delivery thread, or delivery of the second response
        // would deadlock. Single delivery thread here makes that deadlock observable if it regresses.
        service.registerMessageCallbackListener("echo", EchoListener {
            CallbackResponse.success(EchoReply("re:${it.text}"))
        })

        val result = service.request<EchoReply>("echo", EchoRequest("a"))
            .thenApply { first ->
                val second = service.request<EchoReply>("echo", EchoRequest("b")).get(5, TimeUnit.SECONDS)
                "${first.text}|${second.text}"
            }
            .get(10, TimeUnit.SECONDS)

        assertEquals("re:a|re:b", result)
    }

    @Test
    fun `scoped request round-trips on GLOBAL scope`() {
        service.registerMessageCallbackListener("echo", EchoListener {
            CallbackResponse.success(EchoReply("g:${it.text}"))
        }, MessageScope.GLOBAL)

        val reply = service.request<EchoReply>("echo", EchoRequest("hi"), MessageScope.GLOBAL)
            .get(5, TimeUnit.SECONDS)

        assertEquals(EchoReply("g:hi"), reply)
    }
}
