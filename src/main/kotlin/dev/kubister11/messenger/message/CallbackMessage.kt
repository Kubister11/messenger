package dev.kubister11.messenger.message

import dev.kubister11.messenger.scope.MessageScope
import java.util.UUID

data class CallbackMessage(
    val payload: Any?,
    val callbackCallback: String,
    val uuid: UUID = UUID.randomUUID(),
    val callbackScope: MessageScope = MessageScope.LOCAL
)
