package dev.kubister11.messenger

import dev.kubister11.messenger.message.CallbackMessage
import dev.kubister11.messenger.scope.MessageScope
import dev.kubister11.messenger.serialization.MessengerFory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

data class Registered(val a: String, val b: List<Int>, val c: Map<String, Int>)
data class Unregistered(val value: String)

class ForyRegistrationTest {

    @Test
    fun `MessageScope and CallbackMessage are pre-registered by MessengerFory`() {
        // No user registration at all; these library types must still round-trip.
        val fory = MessengerFory.create { }
        val message = CallbackMessage(payload = null, callbackCallback = "c", callbackScope = MessageScope.GLOBAL)
        val decoded = assertIs<CallbackMessage>(fory.deserialize(fory.serialize(message)))
        assertEquals(message, decoded)
    }

    @Test
    fun `registered complex type round-trips with collections intact`() {
        val fory = MessengerFory.create { it.register(Registered::class.java) }
        val value = Registered("x", listOf(1, 2, 3), mapOf("k" to 9))
        val decoded = assertIs<Registered>(fory.deserialize(fory.serialize(value)))
        assertEquals(value, decoded)
    }

    @Test
    fun `requireClassRegistration rejects an unregistered class`() {
        val fory = MessengerFory.create(requireClassRegistration = true) { }
        assertFailsWith<Throwable> { fory.serialize(Unregistered("nope")) }
    }

    @Test
    fun `requireClassRegistration allows an explicitly registered class`() {
        val fory = MessengerFory.create(requireClassRegistration = true) { it.register(Registered::class.java) }
        val value = Registered("y", listOf(4), mapOf())
        val decoded = assertIs<Registered>(fory.deserialize(fory.serialize(value)))
        assertEquals(value, decoded)
    }
}
