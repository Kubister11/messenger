package dev.kubister11.messenger.support

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * A broker container driven straight through the `docker` CLI, pinned to a fixed host port so it
 * survives a stop/start with the same address — exactly what an autoreconnect test needs (the client
 * must find the broker again at the address it originally connected to).
 *
 * The CLI is used instead of a Docker-API client to avoid API-version negotiation problems with the
 * local daemon.
 */
class BrokerContainer(private val image: String, private val containerPort: Int) : AutoCloseable {

    val hostPort: Int = freePort()
    val host: String = "127.0.0.1"

    private var containerId: String = ""

    fun start(): BrokerContainer {
        containerId = docker("run", "-d", "-p", "$hostPort:$containerPort", image).trim()
        check(containerId.isNotBlank()) { "docker run returned no container id" }
        awaitPort()
        return this
    }

    /** Full stop + start of the same container, keeping the pinned host port. Simulates a broker outage. */
    fun restart() {
        docker("restart", "-t", "0", containerId)
        awaitPort()
    }

    override fun close() {
        if (containerId.isNotBlank()) runCatching { docker("rm", "-f", containerId) }
    }

    private fun awaitPort() {
        val up = awaitUntil(Duration.ofSeconds(30), Duration.ofMillis(200)) {
            runCatching {
                Socket().use { it.connect(InetSocketAddress(host, hostPort), 500) }
                true
            }.getOrDefault(false)
        }
        check(up) { "broker $image did not open port $hostPort in time" }
    }

    private fun docker(vararg args: String): String {
        val process = ProcessBuilder(listOf("docker", *args))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "docker ${args.joinToString(" ")} timed out" }
        check(process.exitValue() == 0) { "docker ${args.joinToString(" ")} failed: $output" }
        return output
    }

    private companion object {
        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}

fun dockerAvailable(): Boolean = try {
    val process = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
    process.inputStream.readBytes()
    process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0
} catch (_: Throwable) {
    false
}

/** Polls [condition] until true or [timeout] elapses. Returns whether it became true. */
fun awaitUntil(timeout: Duration = Duration.ofSeconds(10), step: Duration = Duration.ofMillis(100), condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(step.toMillis())
    }
    return condition()
}
