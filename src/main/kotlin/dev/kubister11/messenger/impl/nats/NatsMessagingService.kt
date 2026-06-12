package dev.kubister11.messenger.impl.nats

import dev.kubister11.messenger.MessagingService
import dev.kubister11.messenger.config.NatsConfiguration
import dev.kubister11.messenger.listener.MessageListener
import dev.kubister11.messenger.scope.MessageScope
import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.Message
import io.nats.client.Nats
import io.nats.client.Options
import org.apache.fory.ThreadSafeFory
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

class NatsMessagingService(
    private val url: String,
    private val username: String?,
    private val password: String?,
    private val channel: String,
    private val globalChannel: String,
    private val prefix: String = "",
    private val fory: ThreadSafeFory
) : MessagingService() {

    constructor(
        configuration: NatsConfiguration,
        fory: ThreadSafeFory
    ) : this(
        configuration.url,
        configuration.username.ifBlank { null },
        configuration.password.ifBlank { null },
        configuration.channel,
        configuration.globalChannel,
        configuration.prefix,
        fory
    )

    private companion object {
        private val logger = LoggerFactory.getLogger(NatsMessagingService::class.java)
    }

    lateinit var connection: Connection

    private var dispatcher: Dispatcher? = null
    private val listeners: MutableMap<String, MutableList<Subscription<*>>> = ConcurrentHashMap()

    private class Subscription<DATA : Any>(val clazz: Class<DATA>, val listener: MessageListener<DATA>) {
        fun deliver(packet: Any) {
            if (this.clazz.isInstance(packet)) this.listener.onMessage(this.clazz.cast(packet))
        }
    }

    override fun ready(): Boolean {
        return this::connection.isInitialized && this.connection.status == Connection.Status.CONNECTED
    }

    private fun scopedChannel(channel: String, scope: MessageScope): String {
        val namespace = when (scope) {
            MessageScope.GLOBAL -> this.globalChannel
            MessageScope.LOCAL -> this.channel
        }
        return if (this.prefix.isBlank()) "$namespace.$channel" else "${this.prefix}.$namespace.$channel"
    }

    override fun connect(): Boolean {
        try {
            val optionsBuilder = Options.Builder()
                .server(this.url)

            if (this.username != null && this.password != null) {
                optionsBuilder.userInfo(this.username, this.password)
            }

            this.connection = Nats.connect(optionsBuilder.build())
            this.dispatcher = this.connection.createDispatcher(::handle)
            return true
        } catch (exception: Exception) {
            logger.error("Failed to connect to NATS at {}", this.url, exception)
            return false
        }
    }

    private fun handle(message: Message) {
        val subscriptions = this.listeners[message.subject] ?: return
        val packet = try {
            this.fory.deserialize(message.data)
        } catch (throwable: Throwable) {
            logger.warn("Failed to deserialize message on subject {}", message.subject, throwable)
            return
        } ?: return

        for (subscription in subscriptions) {
            try {
                subscription.deliver(packet)
            } catch (throwable: Throwable) {
                logger.error("Listener on subject {} threw", message.subject, throwable)
            }
        }
    }

    override fun <DATA : Any> registerMessageListener(
        channel: String,
        clazz: KClass<DATA>,
        listener: MessageListener<DATA>,
        scope: MessageScope
    ) {
        val dispatcher = this.dispatcher ?: error("NATS connection not initialized")

        val scopedChannel = this.scopedChannel(channel, scope)
        this.listeners.computeIfAbsent(scopedChannel) {
            dispatcher.subscribe(it)
            CopyOnWriteArrayList()
        }.add(Subscription(clazz.java, listener))
    }

    override fun publish(channel: String, data: Any, scope: MessageScope) {
        if (!this.ready()) error("NATS connection not initialized")

        this.connection.publish(
            this.scopedChannel(channel, scope),
            this.fory.serialize(data)
        )
    }

    override fun publishAsync(channel: String, data: Any, scope: MessageScope): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()

        try {
            this.publish(channel, data, scope)
            future.complete(Unit)
        } catch (exception: Exception) {
            future.completeExceptionally(exception)
        }

        return future
    }

    override fun isMessageListenerRegistered(channel: String, scope: MessageScope): Boolean {
        return this.listeners.containsKey(this.scopedChannel(channel, scope))
    }

    override fun disconnect() {
        this.listeners.clear()
        this.dispatcher = null
        if (this::connection.isInitialized) {
            try {
                this.connection.close()
            } catch (throwable: Throwable) {
                logger.warn("Error while closing NATS connection", throwable)
            }
        }
    }
}
