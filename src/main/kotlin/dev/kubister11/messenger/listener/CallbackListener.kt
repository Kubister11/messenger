package dev.kubister11.messenger.listener

import dev.kubister11.messenger.MessagingService
import dev.kubister11.messenger.message.CallbackMessage

/**
 * Completes the pending [MessagingService.request] future matching an incoming callback.
 *
 * Completion is handed off to [MessagingService.callbackExecutor] so that dependent
 * continuations never run — and never block — on the transport's delivery thread.
 */
class CallbackListener(
    private val messagingService: MessagingService
) : MessageListener<CallbackMessage> {
    override fun onMessage(packet: CallbackMessage) {
        val future = this.messagingService.requests.remove(packet.uuid) ?: return
        this.messagingService.callbackExecutor.execute { future.complete(packet.payload) }
    }
}
