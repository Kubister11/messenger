package dev.kubister11.messenger.serialization

import dev.kubister11.messenger.message.CallbackMessage
import dev.kubister11.messenger.scope.MessageScope
import org.apache.fory.ThreadSafeFory
import org.apache.fory.kotlin.ForyKotlin

/**
 * Builds the [ThreadSafeFory] instance used to (de)serialize messages on the wire.
 *
 * Fory embeds concrete class info in the payload, so heterogeneous message types travel through a
 * single channel and are reconstructed to their exact runtime type on the receiving end.
 */
object MessengerFory {

    /**
     * @param requireClassRegistration when `true` (recommended for production), every message class
     *        must be registered up front via [configure]; unknown classes are rejected. When `false`,
     *        Fory will instantiate **arbitrary classes named in the payload** — only acceptable when
     *        every peer on the broker is fully trusted, as it is a remote-code-execution vector
     *        otherwise.
     * @param configure hook to register your own message classes, e.g. `{ it.register(MyMsg::class.java) }`.
     * @param classLoader classloader used to resolve message classes named in the payload. Defaults to
     *        this library's own classloader. **Must** be set to a loader that can see the message
     *        classes when the calling thread's context classloader cannot (e.g. Minecraft plugin
     *        environments, where the context classloader is the system loader but message classes live
     *        in a plugin/platform loader). Otherwise Fory captures the thread context classloader at
     *        build time and name-based deserialization fails with [ClassNotFoundException].
     */
    fun create(
        requireClassRegistration: Boolean = false,
        classLoader: ClassLoader = MessengerFory::class.java.classLoader,
        configure: (ThreadSafeFory) -> Unit = {}
    ): ThreadSafeFory {
        val fory = ForyKotlin.builder()
            .withXlang(false)
            .requireClassRegistration(requireClassRegistration)
            .withRefTracking(true)
            .withClassLoader(classLoader)
            .buildThreadSafeFory()

        fory.register(CallbackMessage::class.java)
        fory.register(MessageScope::class.java)

        configure(fory)
        return fory
    }
}
