package dev.kubister11.messenger

import dev.kubister11.messenger.listener.CallbackListener
import dev.kubister11.messenger.message.CallbackMessage
import dev.kubister11.messenger.serialization.MessengerFory
import dev.kubister11.messenger.support.InMemoryMessagingService
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallbackListenerTest {

    private val service = InMemoryMessagingService(MessengerFory.create { }).also { it.connect() }

    @AfterTest
    fun tearDown() = service.close()

    @Test
    fun `completes and removes the matching pending request`() {
        val uuid = UUID.randomUUID()
        val future = CompletableFuture<Any?>()
        service.requests[uuid] = future

        CallbackListener(service).onMessage(CallbackMessage(payload = "pong", callbackCallback = "c", uuid = uuid))

        assertEquals("pong", future.get(5, TimeUnit.SECONDS))
        assertFalse(service.requests.containsKey(uuid))
    }

    @Test
    fun `ignores an unknown correlation id and leaves other requests intact`() {
        val known = UUID.randomUUID()
        val knownFuture = CompletableFuture<Any?>()
        service.requests[known] = knownFuture

        CallbackListener(service).onMessage(CallbackMessage(payload = "x", callbackCallback = "c", uuid = UUID.randomUUID()))

        assertFalse(knownFuture.isDone)
        assertTrue(service.requests.containsKey(known))
    }

    @Test
    fun `completes with a null payload`() {
        val uuid = UUID.randomUUID()
        val future = CompletableFuture<Any?>()
        service.requests[uuid] = future

        CallbackListener(service).onMessage(CallbackMessage(payload = null, callbackCallback = "c", uuid = uuid))

        assertEquals(null, future.get(5, TimeUnit.SECONDS))
    }
}
