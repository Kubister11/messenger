package dev.kubister11.messenger

import dev.kubister11.messenger.message.CallbackMessage
import dev.kubister11.messenger.scope.MessageScope
import dev.kubister11.messenger.serialization.MessengerFory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

data class Hello(val name: String, val count: Int)

class ForySerializationTest {

    private val fory = MessengerFory.create {  }

    @Test
    fun `round trips a callback message carrying a user payload`() {
        val message = CallbackMessage(
            payload = Hello("kubister", 7),
            callbackCallback = "greeting_callback",
            uuid = UUID.randomUUID(),
            callbackScope = MessageScope.GLOBAL
        )

        val bytes = fory.serialize(message)
        val decoded = assertIs<CallbackMessage>(fory.deserialize(bytes))

        assertEquals(message, decoded)
        assertEquals(Hello("kubister", 7), decoded.payload)
        assertEquals(MessageScope.GLOBAL, decoded.callbackScope)
    }

    @Test
    fun `round trips a null payload`() {
        val message = CallbackMessage(payload = null, callbackCallback = "c")
        val decoded = assertIs<CallbackMessage>(fory.deserialize(fory.serialize(message)))
        assertEquals(message, decoded)
    }
}
