package dev.kubister11.messenger

import dev.kubister11.messenger.listener.CallbackMessageListener
import dev.kubister11.messenger.message.CallbackMessage
import dev.kubister11.messenger.message.CallbackResponse
import dev.kubister11.messenger.scope.MessageScope
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

data class Ping(val text: String)
data class Pong(val text: String)

class PingListener : CallbackMessageListener<Ping, Pong>() {
    override fun onMessage(packet: Ping): CallbackResponse<Pong> =
        CallbackResponse.success(Pong("re: ${packet.text}"))
}

class CallbackMessageListenerTest {

    private fun listenerWith(captor: AtomicReference<CallbackMessage?>): PingListener {
        val service = object : RecordingMessagingService() {
            override fun publish(channel: String, data: Any, scope: MessageScope) {
                captor.set(data as CallbackMessage)
            }
        }
        return PingListener().also { it.messagingService = service }
    }

    @Test
    fun `infers request type from generic supertype and replies`() {
        val captor = AtomicReference<CallbackMessage?>()
        val listener = listenerWith(captor)

        listener.onMessage(CallbackMessage(Ping("hi"), "ping_callback"))

        assertEquals(Pong("re: hi"), captor.get()?.payload)
    }

    @Test
    fun `ignores payloads of the wrong type`() {
        val captor = AtomicReference<CallbackMessage?>()
        val listener = listenerWith(captor)

        listener.onMessage(CallbackMessage("not a ping", "ping_callback"))

        assertNull(captor.get())
    }
}

/** Minimal stub that records [respond] traffic without a real backend. */
private abstract class RecordingMessagingService : MessagingService() {
    override fun ready(): Boolean = true
    override fun connect(): Boolean = true
    override fun <DATA : Any> registerMessageListener(channel: String, clazz: kotlin.reflect.KClass<DATA>, listener: dev.kubister11.messenger.listener.MessageListener<DATA>, scope: MessageScope) {}
    override fun publishAsync(channel: String, data: Any, scope: MessageScope): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    override fun isMessageListenerRegistered(channel: String, scope: MessageScope): Boolean = true
    override fun disconnect() {}
}
