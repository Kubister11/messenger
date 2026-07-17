package dev.kubister11.messenger

import dev.kubister11.messenger.listener.CallbackMessageListener
import dev.kubister11.messenger.listener.MessageListener
import dev.kubister11.messenger.message.CallbackMessage
import dev.kubister11.messenger.message.CallbackResponse
import dev.kubister11.messenger.scope.MessageScope
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Records everything published via [respond] without a real backend. */
private class RecordingService : MessagingService() {
    val published = CopyOnWriteArrayList<Triple<String, Any, MessageScope>>()
    override fun ready() = true
    override fun connect() = true
    override fun <DATA : Any> registerMessageListener(channel: String, clazz: KClass<DATA>, listener: MessageListener<DATA>, scope: MessageScope) {}
    override fun publish(channel: String, data: Any, scope: MessageScope) { published.add(Triple(channel, data, scope)) }
    override fun publishAsync(channel: String, data: Any, scope: MessageScope) = CompletableFuture.completedFuture(Unit)
    override fun isMessageListenerRegistered(channel: String, scope: MessageScope) = true
    override fun disconnect() {}
}

private class SkipListener : CallbackMessageListener<EchoRequest, EchoReply>() {
    override fun onMessage(packet: EchoRequest) = CallbackResponse.skip<EchoReply>()
}

private class ThrowingListener : CallbackMessageListener<EchoRequest, EchoReply>() {
    override fun onMessage(packet: EchoRequest): CallbackResponse<EchoReply> = error("boom")
}

private class FutureListener(val gate: CompletableFuture<EchoReply?>) : CallbackMessageListener<EchoRequest, EchoReply>() {
    override fun onMessage(packet: EchoRequest) = CallbackResponse.future(gate)
}

// Two-level hierarchy with the request type fixed at the intermediate class.
private open class BaseEcho : CallbackMessageListener<EchoRequest, EchoReply>() {
    override fun onMessage(packet: EchoRequest) = CallbackResponse.success(EchoReply("base:${packet.text}"))
}
private class ChildEcho : BaseEcho()

class CallbackMessageListenerExtraTest {

    @Test
    fun `SKIP response publishes nothing`() {
        val service = RecordingService()
        val listener = SkipListener().also { it.messagingService = service }

        listener.onMessage(CallbackMessage(EchoRequest("hi"), "echo_callback"))

        assertTrue(service.published.isEmpty())
    }

    @Test
    fun `a throwing handler is contained and publishes nothing`() {
        val service = RecordingService()
        val listener = ThrowingListener().also { it.messagingService = service }

        // Must not propagate out of onMessage (would kill the transport delivery thread).
        listener.onMessage(CallbackMessage(EchoRequest("hi"), "echo_callback"))

        assertTrue(service.published.isEmpty())
    }

    @Test
    fun `FUTURE response is only published once the future resolves`() {
        val service = RecordingService()
        val gate = CompletableFuture<EchoReply?>()
        val listener = FutureListener(gate).also { it.messagingService = service }

        listener.onMessage(CallbackMessage(EchoRequest("hi"), "echo_callback", callbackScope = MessageScope.GLOBAL))
        assertTrue(service.published.isEmpty())

        gate.complete(EchoReply("done"))

        assertEquals(1, service.published.size)
        val (channel, data, scope) = service.published.first()
        assertEquals("echo_callback", channel)
        assertEquals(EchoReply("done"), (data as CallbackMessage).payload)
        assertEquals(MessageScope.GLOBAL, scope)
    }

    @Test
    fun `response preserves the request uuid for correlation`() {
        val service = RecordingService()
        val listener = BaseEcho().also { it.messagingService = service }
        val request = CallbackMessage(EchoRequest("hi"), "echo_callback")

        listener.onMessage(request)

        assertEquals(request.uuid, (service.published.single().second as CallbackMessage).uuid)
    }

    @Test
    fun `request type resolves through a two-level subclass hierarchy`() {
        val service = RecordingService()
        val listener = ChildEcho().also { it.messagingService = service }

        listener.onMessage(CallbackMessage(EchoRequest("hi"), "echo_callback"))

        assertEquals(EchoReply("base:hi"), (service.published.single().second as CallbackMessage).payload)
    }

    @Test
    fun `an anonymous listener with an unresolved request type fails fast`() {
        // Generic type erased to a type variable cannot be resolved; construction must fail loudly.
        assertFailsWith<IllegalStateException> {
            makeErased<EchoRequest, EchoReply> { CallbackResponse.success(EchoReply(it.toString())) }
        }
    }

    private fun <REQ, RESP> makeErased(handler: (REQ) -> CallbackResponse<RESP>): CallbackMessageListener<REQ, RESP> =
        object : CallbackMessageListener<REQ, RESP>() {
            override fun onMessage(packet: REQ) = handler(packet)
        }
}
