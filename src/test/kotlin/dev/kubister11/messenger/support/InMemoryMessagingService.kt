package dev.kubister11.messenger.support

import dev.kubister11.messenger.MessagingService
import dev.kubister11.messenger.listener.MessageListener
import dev.kubister11.messenger.scope.MessageScope
import org.apache.fory.ThreadSafeFory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

/**
 * A fully in-process [MessagingService] used to exercise the transport-agnostic request/response,
 * callback and lifecycle machinery without a real broker.
 *
 * Behaviour that mirrors the real transports on purpose:
 *  - every published payload is round-tripped through [ThreadSafeFory] so listeners receive a copy,
 *    exactly like Redis/NATS; this also catches missing type registrations.
 *  - delivery happens on a dedicated single-thread "delivery" executor, so this harness reproduces
 *    the delivery-thread / callback-thread separation the real services rely on to avoid deadlocks.
 */
class InMemoryMessagingService(
    private val fory: ThreadSafeFory,
    private val deliverSynchronously: Boolean = false
) : MessagingService() {

    private class Subscription<DATA : Any>(val clazz: Class<DATA>, val listener: MessageListener<DATA>) {
        fun deliver(packet: Any) {
            if (clazz.isInstance(packet)) listener.onMessage(clazz.cast(packet))
        }
    }

    private val listeners: MutableMap<String, MutableList<Subscription<*>>> = ConcurrentHashMap()
    private val connected = AtomicBoolean(false)

    /** When set, every [publishAsync] fails with this error — used to test publish-failure paths. */
    @Volatile
    var publishFailure: Throwable? = null

    /** Single delivery thread — stands in for the transport's delivery thread. */
    val deliveryExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "inmemory-delivery").apply { isDaemon = true }
    }

    /** Records the thread that last ran a listener, so tests can assert on delivery-thread identity. */
    @Volatile
    var lastDeliveryThread: Thread? = null

    private fun key(channel: String, scope: MessageScope): String = "$scope::$channel"

    override fun ready(): Boolean = connected.get()

    override fun connect(): Boolean {
        connected.set(true)
        return true
    }

    override fun <DATA : Any> registerMessageListener(
        channel: String,
        clazz: KClass<DATA>,
        listener: MessageListener<DATA>,
        scope: MessageScope
    ) {
        listeners.computeIfAbsent(key(channel, scope)) { CopyOnWriteArrayList() }
            .add(Subscription(clazz.java, listener))
    }

    override fun publish(channel: String, data: Any, scope: MessageScope) {
        if (!ready()) error("not ready")
        val subs = listeners[key(channel, scope)] ?: return
        val bytes = fory.serialize(data)

        val task = Runnable {
            lastDeliveryThread = Thread.currentThread()
            val decoded = fory.deserialize(bytes) ?: return@Runnable
            for (sub in subs) sub.deliver(decoded)
        }
        if (deliverSynchronously) task.run() else deliveryExecutor.execute(task)
    }

    override fun publishAsync(channel: String, data: Any, scope: MessageScope): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        publishFailure?.let {
            future.completeExceptionally(it)
            return future
        }
        try {
            publish(channel, data, scope)
            future.complete(Unit)
        } catch (throwable: Throwable) {
            future.completeExceptionally(throwable)
        }
        return future
    }

    override fun isMessageListenerRegistered(channel: String, scope: MessageScope): Boolean =
        listeners.containsKey(key(channel, scope))

    override fun disconnect() {
        connected.set(false)
        listeners.clear()
        deliveryExecutor.shutdown()
    }
}
