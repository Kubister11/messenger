package dev.kubister11.messenger

import dev.kubister11.messenger.config.NatsConfiguration
import dev.kubister11.messenger.config.RedisConfiguration
import dev.kubister11.messenger.impl.empty.EmptyMessagingService
import dev.kubister11.messenger.impl.nats.NatsMessagingService
import dev.kubister11.messenger.impl.redis.RedisMessagingService
import dev.kubister11.messenger.impl.redis.codec.RedisForyCodec
import dev.kubister11.messenger.listener.CallbackListener
import dev.kubister11.messenger.listener.CallbackMessageListener
import dev.kubister11.messenger.listener.MessageListener
import dev.kubister11.messenger.message.CallbackMessage
import dev.kubister11.messenger.scope.MessageScope
import dev.kubister11.messenger.type.MessagingType
import org.apache.fory.ThreadSafeFory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

/**
 * A transport-agnostic publish/subscribe and request/response messaging API.
 *
 * Implementations are thread-safe. Always [close] the service when done — it releases the
 * underlying connection and fails any in-flight requests.
 */
abstract class MessagingService : AutoCloseable {

    companion object {
        /** Default time a [request] waits for a response before failing with a timeout. */
        val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

        /**
         * Builds a [MessagingService] for the given [type].
         *
         * - [MessagingType.REDIS] requires [redis].
         * - [MessagingType.NATS] requires [nats].
         * - [MessagingType.NONE] returns a no-op service.
         *
         * The returned service is not connected yet; call [connect] first.
         */
        fun create(
            type: MessagingType,
            fory: ThreadSafeFory,
            redis: RedisConfiguration? = null,
            nats: NatsConfiguration? = null
        ): MessagingService = when (type) {
            MessagingType.REDIS -> RedisMessagingService(
                requireNotNull(redis) { "RedisConfiguration required for MessagingType.REDIS" },
                RedisForyCodec(fory)
            )

            MessagingType.NATS -> NatsMessagingService(
                requireNotNull(nats) { "NatsConfiguration required for MessagingType.NATS" },
                fory
            )

            MessagingType.NONE -> EmptyMessagingService()
        }
    }

    val requests: MutableMap<UUID, CompletableFuture<Any?>> = ConcurrentHashMap()

    /**
     * Executor used to complete [request] futures and, by transport implementations, to run
     * message delivery itself.
     *
     * Completing futures directly on the transport's delivery thread would run dependent
     * continuations (`thenAccept`, `thenApply`, ...) on that thread — if any of them blocks
     * waiting for another message (e.g. `request(...).join()`), delivery stops and the wait
     * deadlocks until it times out. Transports (NATS' single dispatcher thread, Redisson's small
     * fixed pool) are just as vulnerable to being clogged by ordinary listener work under heavy
     * traffic, which is why they route delivery through this executor too. A cached pool keeps
     * both off the delivery thread and grows with load instead of capping throughput at the
     * transport's thread count.
     */
    internal val callbackExecutor: ExecutorService = Executors.newCachedThreadPool(
        object : ThreadFactory {
            private val counter = AtomicInteger()
            override fun newThread(runnable: Runnable): Thread =
                Thread(runnable, "messenger-callback-${this.counter.incrementAndGet()}")
                    .apply { isDaemon = true }
        }
    )

    abstract fun ready(): Boolean

    abstract fun connect(): Boolean
    abstract fun <DATA : Any> registerMessageListener(channel: String, clazz: KClass<DATA>, listener: MessageListener<DATA>, scope: MessageScope = MessageScope.LOCAL)
    abstract fun publish(channel: String, data: Any, scope: MessageScope = MessageScope.LOCAL)
    abstract fun publishAsync(channel: String, data: Any, scope: MessageScope = MessageScope.LOCAL): CompletableFuture<Unit>
    abstract fun isMessageListenerRegistered(channel: String, scope: MessageScope = MessageScope.LOCAL): Boolean

    /** Releases transport-specific resources. Implementations must be idempotent. */
    protected abstract fun disconnect()

    fun registerMessageCallbackListener(channel: String, listener: CallbackMessageListener<*, *>, scope: MessageScope = MessageScope.LOCAL) {
        listener.messagingService = this
        this.registerMessageListener(channel, CallbackMessage::class, listener, scope)
    }

    /**
     * Sends [data] on [channel] and returns a future completed with the peer's response.
     *
     * The future fails with [java.util.concurrent.TimeoutException] after [timeout] and with the
     * publish error if the message could not be sent. The correlation entry is always cleaned up.
     */
    open fun <RESPONSE> request(
        channel: String,
        data: Any,
        scope: MessageScope = MessageScope.LOCAL,
        timeout: Duration = DEFAULT_REQUEST_TIMEOUT
    ): CompletableFuture<RESPONSE> {
        if (!this.ready()) error("Messaging Service is not ready!")

        val completableFuture = CompletableFuture<RESPONSE>()

        val callbackChannel = channel + "_callback"

        if (!this.isMessageListenerRegistered(callbackChannel, scope)) {
            this.registerMessageListener(
                callbackChannel,
                CallbackMessage::class,
                CallbackListener(this),
                scope
            )
        }

        val packet = CallbackMessage(
            data,
            callbackChannel,
            callbackScope = scope
        )

        @Suppress("UNCHECKED_CAST")
        this.requests[packet.uuid] = completableFuture as CompletableFuture<Any?>

        completableFuture.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .whenComplete { _, _ -> this.requests.remove(packet.uuid) }

        try {
            this.publishAsync(channel, packet, scope).whenComplete { _, throwable ->
                if (throwable != null) completableFuture.completeExceptionally(throwable)
            }
        } catch (throwable: Throwable) {
            completableFuture.completeExceptionally(throwable)
        }

        return completableFuture
    }

    fun respond(response: CallbackMessage) {
        this.publish(response.callbackCallback, response, response.callbackScope)
    }

    /** Fails all in-flight requests and releases the underlying connection. Safe to call more than once. */
    final override fun close() {
        val closed = CancellationException("Messaging service closed")
        val iterator = this.requests.values.iterator()
        while (iterator.hasNext()) {
            iterator.next().completeExceptionally(closed)
            iterator.remove()
        }
        this.disconnect()
        this.callbackExecutor.shutdown()
    }
}
