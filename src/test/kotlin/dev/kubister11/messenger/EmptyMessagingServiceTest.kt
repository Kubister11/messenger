package dev.kubister11.messenger

import dev.kubister11.messenger.impl.empty.EmptyMessagingService
import dev.kubister11.messenger.listener.MessageListener
import dev.kubister11.messenger.scope.MessageScope
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class EmptyMessagingServiceTest {

    @Test
    fun `is always ready and connects trivially`() {
        val service = EmptyMessagingService()
        assertTrue(service.ready())
        assertTrue(service.connect())
    }

    @Test
    fun `publish and register are no-ops that never throw`() {
        val service = EmptyMessagingService()
        service.publish("c", "data")
        service.publishAsync("c", "data").get(1, TimeUnit.SECONDS)
        service.registerMessageListener("c", String::class, object : MessageListener<String> {
            override fun onMessage(packet: String) {}
        }, MessageScope.LOCAL)
    }

    @Test
    fun `reports every listener as registered`() {
        val service = EmptyMessagingService()
        assertTrue(service.isMessageListenerRegistered("anything"))
    }
}
