package dev.kubister11.messenger

import dev.kubister11.messenger.impl.empty.EmptyMessagingService
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LifecycleTest {

    @Test
    fun `close cancels in-flight requests and clears the map`() {
        val service = EmptyMessagingService()
        val future = service.request<String>("ping", "hello")

        assertEquals(1, service.requests.size)

        service.close()

        assertTrue(service.requests.isEmpty())
        // CompletableFuture.get() rethrows a CancellationException directly rather than wrapping it.
        assertFailsWith<CancellationException> { future.get() }
    }

    @Test
    fun `request times out and removes its correlation entry`() {
        val service = EmptyMessagingService()
        val future = service.request<String>("ping", "hello", timeout = Duration.ofMillis(50))

        val error = assertFailsWith<ExecutionException> { future.get() }
        assertTrue(error.cause is TimeoutException)
        assertTrue(service.requests.isEmpty())
    }

    @Test
    fun `close is idempotent`() {
        val service = EmptyMessagingService()
        service.close()
        service.close()
    }
}
