package dev.kubister11.messenger

import dev.kubister11.messenger.config.NatsConfiguration
import dev.kubister11.messenger.config.RedisConfiguration
import dev.kubister11.messenger.impl.empty.EmptyMessagingService
import dev.kubister11.messenger.impl.nats.NatsMessagingService
import dev.kubister11.messenger.impl.redis.RedisMessagingService
import dev.kubister11.messenger.serialization.MessengerFory
import dev.kubister11.messenger.type.MessagingType
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class MessagingServiceFactoryTest {

    private val fory = MessengerFory.create { }

    @Test
    fun `NONE returns a no-op service`() {
        val service = MessagingService.create(MessagingType.NONE, fory)
        assertIs<EmptyMessagingService>(service)
    }

    @Test
    fun `REDIS without configuration fails`() {
        assertFailsWith<IllegalArgumentException> {
            MessagingService.create(MessagingType.REDIS, fory, redis = null)
        }
    }

    @Test
    fun `NATS without configuration fails`() {
        assertFailsWith<IllegalArgumentException> {
            MessagingService.create(MessagingType.NATS, fory, nats = null)
        }
    }

    @Test
    fun `REDIS with configuration builds a Redis service that is not yet connected`() {
        val service = MessagingService.create(
            MessagingType.REDIS,
            fory,
            redis = RedisConfiguration(host = "localhost", channel = "svc", globalChannel = "global")
        )
        assertIs<RedisMessagingService>(service)
        assertFalse(service.ready())
    }

    @Test
    fun `NATS with configuration builds a NATS service that is not yet connected`() {
        val service = MessagingService.create(
            MessagingType.NATS,
            fory,
            nats = NatsConfiguration(url = "nats://localhost:4222", channel = "svc", globalChannel = "global")
        )
        assertIs<NatsMessagingService>(service)
        assertFalse(service.ready())
    }
}
