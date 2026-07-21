package dev.kubister11.messenger.impl.redis

import dev.kubister11.messenger.MessagingService
import dev.kubister11.messenger.config.RedisConfiguration
import dev.kubister11.messenger.listener.MessageListener
import dev.kubister11.messenger.scope.MessageScope
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.client.codec.Codec
import org.redisson.config.Config
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import kotlin.reflect.KClass

class RedisMessagingService(
    private val host: String,
    private val port: Int,
    private val password: String?,
    private val channel: String,
    private val globalChannel: String,
    private val prefix: String = "",
    private val codec: Codec? = null,
) : MessagingService() {

    constructor(
        configuration: RedisConfiguration,
        codec: Codec?
    ) : this(
        configuration.host,
        configuration.port,
        configuration.password.ifBlank { null },
        configuration.channel,
        configuration.globalChannel,
        configuration.prefix,
        codec
    )

    private companion object {
        private val logger = LoggerFactory.getLogger(RedisMessagingService::class.java)
    }

    lateinit var client: RedissonClient

    override fun ready(): Boolean = this::client.isInitialized && !this.client.isShutdown

    private fun scopedChannel(channel: String, scope: MessageScope): String {
        val namespace = when (scope) {
            MessageScope.GLOBAL -> this.globalChannel
            MessageScope.LOCAL -> this.channel
        }
        return if (this.prefix.isBlank()) "$namespace::$channel" else "${this.prefix}@$namespace::$channel"
    }

    override fun connect(): Boolean {
        try {
            val config = Config()

            config.useSingleServer()
                .setAddress("redis://$host:$port")

            if (password != null) config.setPassword(password)

            config.setThreads(3)
            config.setNettyThreads(3)

            if (codec != null) config.setCodec(codec)

            client = Redisson.create(config)
            return true
        } catch (exception: Exception) {
            logger.error("Failed to connect to Redis at {}:{}", host, port, exception)
            return false
        }
    }

    override fun <DATA : Any> registerMessageListener(
        channel: String,
        clazz: KClass<DATA>,
        listener: MessageListener<DATA>,
        scope: MessageScope
    ) {
        if (!this.ready()) error("Redis client not initialized")

        val redisListener = RedisMessageListener(listener, this.callbackExecutor)
        this.client.getTopic(this.scopedChannel(channel, scope)).addListener(clazz.java, redisListener)
    }

    override fun publish(channel: String, data: Any, scope: MessageScope) {
        if (!this.ready()) error("Redis client not initialized")

        this.client.getTopic(this.scopedChannel(channel, scope)).publish(data)
    }

    override fun publishAsync(channel: String, data: Any, scope: MessageScope): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()

        if (!this.ready()) {
            future.completeExceptionally(IllegalStateException("Redis client not initialized"))
            return future
        }

        this.client.getTopic(this.scopedChannel(channel, scope)).publishAsync(data)
            .whenComplete { _, throwable ->
                if (throwable != null) future.completeExceptionally(throwable) else future.complete(Unit)
            }
        return future
    }

    override fun isMessageListenerRegistered(channel: String, scope: MessageScope): Boolean {
        return this.client.getTopic(this.scopedChannel(channel, scope)).countListeners() > 0
    }

    override fun disconnect() {
        if (this::client.isInitialized && !this.client.isShutdown) {
            this.client.shutdown()
        }
    }
}
