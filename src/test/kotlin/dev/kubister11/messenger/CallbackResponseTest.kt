package dev.kubister11.messenger

import dev.kubister11.messenger.message.CallbackResponse
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallbackResponseTest {

    @Test
    fun `success wraps an already-completed future of the value`() {
        val response = CallbackResponse.success("ok")

        assertEquals(CallbackResponse.Type.SUCCESS, response.type)
        assertTrue(response.response.isDone)
        assertEquals("ok", response.response.get())
    }

    @Test
    fun `skip carries a completed null future and the SKIP type`() {
        val response = CallbackResponse.skip<String>()

        assertEquals(CallbackResponse.Type.SKIP, response.type)
        assertTrue(response.response.isDone)
        assertNull(response.response.get())
    }

    @Test
    fun `future keeps the provided pending future and the FUTURE type`() {
        val pending = CompletableFuture<String?>()
        val response = CallbackResponse.future(pending)

        assertEquals(CallbackResponse.Type.FUTURE, response.type)
        assertFalse(response.response.isDone)

        pending.complete("later")
        assertEquals("later", response.response.get())
    }
}
