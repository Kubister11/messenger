package dev.kubister11.messenger

import dev.kubister11.messenger.config.NatsConfiguration
import dev.kubister11.messenger.listener.CallbackMessageListener
import dev.kubister11.messenger.listener.MessageListener
import dev.kubister11.messenger.message.CallbackResponse
import dev.kubister11.messenger.scope.MessageScope
import dev.kubister11.messenger.serialization.MessengerFory
import dev.kubister11.messenger.support.BrokerContainer
import dev.kubister11.messenger.support.awaitUntil
import dev.kubister11.messenger.support.dockerAvailable
import dev.kubister11.messenger.type.MessagingType
import org.apache.fory.ThreadSafeFory
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

data class NatsMsg(val text: String)

class NatsIntegrationTest {

    private lateinit var broker: BrokerContainer

    private fun fory(): ThreadSafeFory = MessengerFory.create {
        it.register(NatsMsg::class.java)
    }

    private fun newService(channel: String = "svc", global: String = "global", prefix: String = ""): MessagingService {
        val service = MessagingService.create(
            MessagingType.NATS,
            fory(),
            nats = NatsConfiguration(
                url = "nats://${broker.host}:${broker.hostPort}",
                channel = channel,
                globalChannel = global,
                prefix = prefix
            )
        )
        check(service.connect()) { "NATS connect failed" }
        return service
    }

    private fun stringListener(sink: ConcurrentLinkedQueue<String>) = object : MessageListener<NatsMsg> {
        override fun onMessage(packet: NatsMsg) { sink.add(packet.text) }
    }

    @BeforeTest
    fun setUp() {
        assumeTrue(dockerAvailable(), "Docker not available")
        broker = BrokerContainer("nats:2-alpine", 4222).start()
    }

    @AfterTest
    fun tearDown() {
        if (::broker.isInitialized) broker.close()
    }

    @Test
    fun `delivers a published message to a subscriber`() {
        val service = newService()
        val received = ConcurrentLinkedQueue<String>()
        service.registerMessageListener("news", NatsMsg::class, stringListener(received))

        assertTrue(awaitUntil { service.isMessageListenerRegistered("news") })
        // Give NATS a moment to propagate the subscription before publishing.
        assertTrue(awaitUntil(Duration.ofSeconds(5)) {
            service.publish("news", NatsMsg("hello"))
            received.contains("hello")
        }, "message never delivered")
        service.close()
    }

    @Test
    fun `request over nats round-trips a response`() {
        val service = newService()
        service.registerMessageCallbackListener("ask", object : CallbackMessageListener<NatsMsg, NatsMsg>() {
            override fun onMessage(packet: NatsMsg) = CallbackResponse.success(NatsMsg("re:${packet.text}"))
        })

        val reply = service.request<NatsMsg>("ask", NatsMsg("ping")).get(10, TimeUnit.SECONDS)

        assertEquals(NatsMsg("re:ping"), reply)
        service.close()
    }

    @Test
    fun `GLOBAL scope crosses two services while LOCAL stays private`() {
        val a = newService(channel = "svcA", global = "global")
        val b = newService(channel = "svcB", global = "global")
        val onB = ConcurrentLinkedQueue<String>()
        b.registerMessageListener("evt", NatsMsg::class, stringListener(onB), MessageScope.GLOBAL)
        b.registerMessageListener("evt", NatsMsg::class, stringListener(onB), MessageScope.LOCAL)

        assertTrue(awaitUntil(Duration.ofSeconds(5)) {
            a.publish("evt", NatsMsg("global-hit"), MessageScope.GLOBAL)
            onB.contains("global-hit")
        }, "GLOBAL message not received cross-service")

        a.publish("evt", NatsMsg("local-miss"), MessageScope.LOCAL)
        Thread.sleep(500)
        assertFalse(onB.contains("local-miss"), "LOCAL message leaked across service namespaces")

        a.close()
        b.close()
    }

    @Test
    fun `resumes delivery after the broker restarts (autoreconnect)`() {
        val service = newService()
        val received = ConcurrentLinkedQueue<String>()
        service.registerMessageListener("hb", NatsMsg::class, stringListener(received))

        assertTrue(awaitUntil(Duration.ofSeconds(5)) {
            service.publish("hb", NatsMsg("before"))
            received.contains("before")
        }, "baseline delivery failed")

        broker.restart()

        // Same service, no reconnect() call — jnats must reconnect and the dispatcher must resubscribe.
        val recovered = awaitUntil(Duration.ofSeconds(40), Duration.ofMillis(500)) {
            runCatching { if (service.ready()) service.publish("hb", NatsMsg("after")) }
            received.contains("after")
        }

        assertTrue(recovered, "service did not autoreconnect / resubscribe after broker restart")
        service.close()
    }
}
