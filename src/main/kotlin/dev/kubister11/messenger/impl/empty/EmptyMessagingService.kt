package dev.kubister11.messenger.impl.empty

import dev.kubister11.messenger.listener.MessageListener
import dev.kubister11.messenger.MessagingService
import dev.kubister11.messenger.scope.MessageScope
import java.util.concurrent.CompletableFuture
import kotlin.reflect.KClass

class EmptyMessagingService : MessagingService() {
    override fun ready(): Boolean {
        return true
    }

    override fun connect(): Boolean {
        return true
    }

    override fun <DATA : Any> registerMessageListener(
        channel: String,
        clazz: KClass<DATA>,
        listener: MessageListener<DATA>,
        scope: MessageScope
    ) { }

    override fun publish(channel: String, data: Any, scope: MessageScope) { }

    override fun publishAsync(channel: String, data: Any, scope: MessageScope): CompletableFuture<Unit> {
        return CompletableFuture.completedFuture(Unit)
    }

    override fun isMessageListenerRegistered(channel: String, scope: MessageScope): Boolean {
        return true
    }

    override fun disconnect() { }
}
