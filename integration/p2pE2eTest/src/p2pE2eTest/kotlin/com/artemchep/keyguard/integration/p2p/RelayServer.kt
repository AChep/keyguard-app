package com.artemchep.keyguard.integration.p2p

import com.artemchep.keyguard.integration.process.CapturedProcess
import com.artemchep.keyguard.integration.process.freePort
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.SecureRandom
import java.time.Duration

/**
 * The relay of `server/relay`, as Gradle built it, listening on a free loopback port
 * with a random access token. Close it to stop the process.
 *
 * With [storage] it keeps items for offline devices in a new temporary directory, which
 * [restart] keeps and [close] deletes.
 */
internal class RelayServer private constructor(
    val port: Int,
    /** The access token devices send. */
    val token: String,
    private val metricsPort: Int,
    private val turn: Turn?,
    private val storage: Storage?,
) : AutoCloseable {
    /** The base URL devices store in their network settings. */
    val url: String = "ws://$LOOPBACK:$port"

    private val mailboxDir: File? = storage?.let {
        Files.createTempDirectory("keyguard-relay-mailbox-").toFile()
    }

    /** Every process of this relay, the running one last: a restart keeps the output of the old one. */
    private val processes = mutableListOf<CapturedProcess>()

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    fun diagnostics(): String = buildString {
        synchronized(processes) {
            processes.forEachIndexed { index, process ->
                append(process.diagnostics(if (index == 0) "relay" else "relay (restart $index)"))
            }
        }
        if (storage != null) {
            val requests = runCatching { mailboxRequests().filterValues { it > 0L } }.getOrElse { it }
            val items = runCatching { mailboxItems() }.getOrElse { it }
            appendLine("relay storage: requests=$requests items=$items")
        }
    }

    /** Stops the relay and starts it again on the same port, with the same token and storage. */
    fun restart() {
        synchronized(processes) { processes.last() }.close()
        launch()
    }

    override fun close() {
        synchronized(processes) { processes.lastOrNull() }?.close()
        mailboxDir?.deleteRecursively()
    }

    /** TURN servers the relay hands out credentials for. */
    class Turn(
        val urls: List<String>,
        val secret: String,
    )

    /**
     * Relay storage. The volume of the temporary directory may have little free space,
     * so by default the relay leaves none of it free.
     *
     * @property minFreeBytes the free space the relay leaves on the volume: one larger
     *   than the disk lowers the limit of stored bytes to 0, and every upload is refused.
     */
    class Storage(
        val minFreeBytes: Long = 0L,
    )

    /**
     * The request counters of relay storage from the metrics of the relay, by `op/result`,
     * for example `put/ok`. Empty without [storage].
     */
    fun mailboxRequests(): Map<String, Long> = metrics()
        .mapNotNull { (key, value) ->
            val match = MAILBOX_REQUESTS.matchEntire(key)
                ?: return@mapNotNull null
            "${match.groupValues[1]}/${match.groupValues[2]}" to value
        }
        .toMap()

    /** The items the relay holds, by kind: `state` or `blob`. */
    fun mailboxItems(): Map<String, Long> = metrics()
        .mapNotNull { (key, value) ->
            val match = MAILBOX_ITEMS.matchEntire(key)
                ?: return@mapNotNull null
            match.groupValues[1] to value
        }
        .toMap()

    /** The samples of the metrics of the relay, by name with labels. */
    private fun metrics(): Map<String, Long> {
        val request = HttpRequest.newBuilder(URI("http://$LOOPBACK:$metricsPort/metrics"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build()
        val body = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body()
        return body.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val name = line.substringBeforeLast(' ')
                val value = line.substringAfterLast(' ').toDoubleOrNull()
                    ?: return@mapNotNull null
                name to value.toLong()
            }
            .toMap()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun launch() {
        val binary = File(
            requireNotNull(System.getProperty(BINARY_PROPERTY)) {
                "Run through Gradle, which builds the relay and sets $BINARY_PROPERTY."
            },
        )
        check(binary.canExecute()) { "The relay binary is not executable: $binary" }
        val builder = ProcessBuilder(binary.absolutePath).apply {
            environment().apply {
                // Only the settings below, whatever the host's environment holds.
                val prefixes = listOf("RELAY_", "STUN_", "TURN_", "ICE_")
                keys.removeAll { key -> prefixes.any { key.startsWith(it) } }
                remove("TRUST_PROXY")
                put("RELAY_BIND", "$LOOPBACK:$port")
                put("RELAY_METRICS_BIND", "$LOOPBACK:$metricsPort")
                put("RELAY_TOKEN", token)
                // Every device of the tests connects from the same address, unlike
                // real ones; the per-connection limits stay as shipped. Devices send
                // their tickets, so the ticketless allowance stays as shipped too.
                put("RELAY_MAX_CONNS_PER_IP", "256")
                put("ICE_REQUESTS_PER_MINUTE", "600")
                put("RUST_LOG", "info")
                if (turn != null) {
                    put("TURN_URLS", turn.urls.joinToString(","))
                    put("TURN_SECRET", turn.secret)
                }
                if (storage != null && mailboxDir != null) {
                    put("RELAY_MAILBOX_DIR", mailboxDir.absolutePath)
                    put("RELAY_MAILBOX_MIN_FREE_BYTES", storage.minFreeBytes.toString())
                    // The devices of the tests ask for the state of their peers far more
                    // often than real ones, all from one address.
                    put("RELAY_MAILBOX_REQUESTS_PER_MINUTE", MAILBOX_REQUESTS_PER_MINUTE.toString())
                }
            }
        }
        val process = CapturedProcess(builder.start(), name = "relay")
        synchronized(processes) { processes += process }
        try {
            awaitHealthy(process)
        } catch (e: Throwable) {
            val diagnostics = diagnostics()
            runCatching { close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw AssertionError("The relay did not become healthy.\n$diagnostics", e)
        }
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val BINARY_PROPERTY = "keyguard.p2pE2e.relayBinary"
        private const val TOKEN_LENGTH = 32
        private const val TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        private const val MAILBOX_REQUESTS_PER_MINUTE = 100_000
        private val STARTUP_TIMEOUT = Duration.ofSeconds(30)
        private const val POLL_MS = 100L

        private val MAILBOX_REQUESTS = Regex("""keyguard_relay_mailbox_requests_total\{op="(\w+)",result="(\w+)"}""")
        private val MAILBOX_ITEMS = Regex("""keyguard_relay_mailbox_items\{kind="(\w+)"}""")

        private val random = SecureRandom()

        /** Starts a relay; with [turn] it also mints TURN credentials, with [storage] it keeps items. */
        fun start(
            turn: Turn? = null,
            storage: Storage? = null,
        ): RelayServer = RelayServer(
            port = freePort(),
            token = randomToken(),
            metricsPort = freePort(),
            turn = turn,
            storage = storage,
        ).also { it.launch() }

        private fun randomToken(): String = buildString {
            repeat(TOKEN_LENGTH) {
                append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)])
            }
        }
    }

    private fun awaitHealthy(process: CapturedProcess) {
        val request = HttpRequest.newBuilder(URI("http://$LOOPBACK:$port/healthz"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build()
        val deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos()
        while (true) {
            check(process.isAlive) { "The relay exited." }
            val status = try {
                httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            } catch (_: IOException) {
                null
            }
            if (status == HTTP_OK) return
            check(System.nanoTime() < deadline) { "No answer from /healthz in $STARTUP_TIMEOUT." }
            Thread.sleep(POLL_MS)
        }
    }
}

private const val HTTP_OK = 200
