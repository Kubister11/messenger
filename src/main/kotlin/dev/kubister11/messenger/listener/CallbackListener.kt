package dev.kubister11.messenger.listener

import dev.kubister11.messenger.MessagingService
import dev.kubister11.messenger.message.CallbackMessage

/** Completes the pending [MessagingService.request] future matching an incoming callback. */
class CallbackListener(
    private val messagingService: MessagingService
) : MessageListener<CallbackMessage> {
    override fun onMessage(packet: CallbackMessage) {
        this.messagingService.requests.remove(packet.uuid)?.complete(packet.payload)
    }
}
