package dev.kubister11.messenger

import dev.kubister11.messenger.config.RedisConfiguration
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

data class RedisMsg(val text: String)

class RedisIntegrationTest {

    private lateinit var broker: BrokerContainer

    private fun fory(): ThreadSafeFory = MessengerFory.create {
        it.register(RedisMsg::class.java)
    }

    private fun newService(channel: String = "svc", global: String = "global", prefix: String = ""): MessagingService {
        val service = MessagingService.create(
            MessagingType.REDIS,
            fory(),
            redis = RedisConfiguration(
                host = broker.host,
                port = broker.hostPort,
                channel = channel,
                globalChannel = global,
                prefix = prefix
            )
        )
        check(service.connect()) { "Redis connect failed" }
        return service
    }

    private fun stringListener(sink: ConcurrentLinkedQueue<String>) = object : MessageListener<RedisMsg> {
        override fun onMessage(packet: RedisMsg) { sink.add(packet.text) }
    }

    @BeforeTest
    fun setUp() {
        assumeTrue(dockerAvailable(), "Docker not available")
        broker = BrokerContainer("redis:7-alpine", 6379).start()
    }

    @AfterTest
    fun tearDown() {
        if (::broker.isInitialized) broker.close()
    }

    @Test
    fun `delivers a published message to a local subscriber`() {
        val service = newService()
        val received = ConcurrentLinkedQueue<String>()
        service.registerMessageListener("news", RedisMsg::class, stringListener(received))

        service.publish("news", RedisMsg("hello"))

        assertTrue(awaitUntil { received.contains("hello") }, "message never delivered")
        service.close()
    }

    @Test
    fun `request over redis round-trips a response`() {
        val service = newService()
        service.registerMessageCallbackListener("ask", object : CallbackMessageListener<RedisMsg, RedisMsg>() {
            override fun onMessage(packet: RedisMsg) = CallbackResponse.success(RedisMsg("re:${packet.text}"))
        })

        val reply = service.request<RedisMsg>("ask", RedisMsg("ping")).get(10, TimeUnit.SECONDS)

        assertEquals(RedisMsg("re:ping"), reply)
        service.close()
    }

    @Test
    fun `GLOBAL scope crosses two services while LOCAL stays private`() {
        val a = newService(channel = "svcA", global = "global")
        val b = newService(channel = "svcB", global = "global")
        val onB = ConcurrentLinkedQueue<String>()
        b.registerMessageListener("evt", RedisMsg::class, stringListener(onB), MessageScope.GLOBAL)
        b.registerMessageListener("evt", RedisMsg::class, stringListener(onB), MessageScope.LOCAL)

        a.publish("evt", RedisMsg("global-hit"), MessageScope.GLOBAL)
        assertTrue(awaitUntil { onB.contains("global-hit") }, "GLOBAL message not received cross-service")

        // A's LOCAL namespace is svcA; B subscribes on svcB LOCAL, so it must NOT receive this.
        a.publish("evt", RedisMsg("local-miss"), MessageScope.LOCAL)
        Thread.sleep(500)
        assertFalse(onB.contains("local-miss"), "LOCAL message leaked across service namespaces")

        a.close()
        b.close()
    }

    @Test
    fun `resumes delivery after the broker restarts (autoreconnect)`() {
        val service = newService()
        val received = ConcurrentLinkedQueue<String>()
        service.registerMessageListener("hb", RedisMsg::class, stringListener(received))

        // Baseline: works before the outage.
        service.publish("hb", RedisMsg("before"))
        assertTrue(awaitUntil { received.contains("before") }, "baseline delivery failed")

        broker.restart()

        // After the broker returns, the same service (no reconnect() call) must resume delivering.
        // Messages published during the outage are lost, so retry until one gets through.
        val recovered = awaitUntil(Duration.ofSeconds(40), Duration.ofMillis(500)) {
            runCatching { if (service.ready()) service.publish("hb", RedisMsg("after")) }
            received.contains("after")
        }

        assertTrue(recovered, "service did not autoreconnect / resubscribe after broker restart")
        service.close()
    }
}
