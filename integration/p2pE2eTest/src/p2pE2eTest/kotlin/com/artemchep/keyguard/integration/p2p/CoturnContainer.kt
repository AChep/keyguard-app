package com.artemchep.keyguard.integration.p2p

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.ToStringConsumer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Duration

/**
 * coturn, the TURN server of `server/relay/docker-compose.yml`, on a loopback UDP port.
 * Unlike the shipped configuration it relays to private addresses: both devices of a
 * test allocate on this one server.
 */
internal class CoturnContainer private constructor(
    private val imageName: DockerImageName,
    /** The loopback UDP port of TURN. */
    val port: Int,
    /** coturn's `static-auth-secret`, which the relay mints credentials with. */
    val secret: String,
) : GenericContainer<CoturnContainer>(imageName) {
    private val logs = ToStringConsumer()

    /** What the relay hands out. */
    val turn: RelayServer.Turn = RelayServer.Turn(
        urls = listOf("turn:$LOOPBACK:$port?transport=udp"),
        secret = secret,
    )

    init {
        withCommand(
            "-n",
            "--log-file=stdout",
            "--listening-port=$TURN_PORT",
            "--min-port=$MIN_RELAY_PORT",
            "--max-port=$MAX_RELAY_PORT",
            "--use-auth-secret",
            "--static-auth-secret=$secret",
            "--realm=keyguard.test",
            "--fingerprint",
            "--no-tls",
            "--allow-loopback-peers",
        )
        // Like `-p 127.0.0.1:<port>:3478/udp`; Testcontainers maps TCP ports only.
        withCreateContainerCmdModifier { command ->
            command.withExposedPorts(ExposedPort.udp(TURN_PORT))
            command.hostConfig?.withPortBindings(
                PortBinding(Ports.Binding.bindIpAndPort(LOOPBACK, port), ExposedPort.udp(TURN_PORT)),
            )
        }
        withLogConsumer(logs)
    }

    fun diagnostics(): String = buildString {
        appendLine("coturn image: ${imageName.asCanonicalNameString()}")
        appendLine("coturn port: $port/udp")
        appendLine("coturn logs:")
        append(logs.toUtf8String())
    }

    /** Waits until coturn answers a STUN binding request through the port mapping. */
    private fun awaitStun() {
        val deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos()
        DatagramSocket().use { socket ->
            socket.soTimeout = STUN_TIMEOUT_MS
            while (true) {
                val transactionId = ByteArray(STUN_TRANSACTION_ID_SIZE).also(random::nextBytes)
                val request = ByteBuffer.allocate(STUN_HEADER_SIZE)
                    .putShort(STUN_BINDING_REQUEST)
                    .putShort(0)
                    .putInt(STUN_MAGIC_COOKIE)
                    .put(transactionId)
                    .array()
                socket.send(DatagramPacket(request, request.size, InetSocketAddress(LOOPBACK, port)))
                val response = DatagramPacket(ByteArray(STUN_BUFFER_SIZE), STUN_BUFFER_SIZE)
                val answered = try {
                    socket.receive(response)
                    val buffer = ByteBuffer.wrap(response.data, 0, response.length)
                    buffer.getShort(0) == STUN_BINDING_SUCCESS &&
                        response.data.copyOfRange(STUN_TRANSACTION_ID_OFFSET, STUN_HEADER_SIZE)
                            .contentEquals(transactionId)
                } catch (_: SocketTimeoutException) {
                    false
                }
                if (answered) return
                check(isRunning) { "coturn exited." }
                check(System.nanoTime() < deadline) { "coturn did not answer STUN in $STARTUP_TIMEOUT." }
            }
        }
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val TURN_PORT = 3478
        private const val MIN_RELAY_PORT = 49152
        private const val MAX_RELAY_PORT = 49200
        private const val SECRET_LENGTH = 32
        private val STARTUP_TIMEOUT = Duration.ofSeconds(60)

        private const val STUN_TIMEOUT_MS = 500
        private const val STUN_HEADER_SIZE = 20
        private const val STUN_TRANSACTION_ID_OFFSET = 8
        private const val STUN_TRANSACTION_ID_SIZE = 12
        private const val STUN_BUFFER_SIZE = 1500
        private const val STUN_BINDING_REQUEST: Short = 0x0001
        private const val STUN_BINDING_SUCCESS: Short = 0x0101
        private const val STUN_MAGIC_COOKIE = 0x2112A442

        private val random = SecureRandom()

        /** Starts coturn with a random secret; the image is the one Compose pins. */
        @Suppress("TooGenericExceptionCaught")
        fun start(): CoturnContainer {
            val secret = buildString {
                repeat(SECRET_LENGTH) { append(HEX[random.nextInt(HEX.length)]) }
            }
            val container = CoturnContainer(DockerImageName.parse(composeImage()), freeUdpPort(), secret)
            try {
                container.start()
                container.awaitStun()
            } catch (e: Throwable) {
                val diagnostics = container.diagnostics()
                runCatching { container.close() }.exceptionOrNull()?.let(e::addSuppressed)
                throw AssertionError("coturn did not become ready.\n$diagnostics", e)
            }
            return container
        }

        /** The coturn image of `server/relay/docker-compose.yml`, digest included. */
        private fun composeImage(): String {
            val repoRoot = requireNotNull(System.getProperty("keyguard.repoRoot")) {
                "Run through Gradle, which sets keyguard.repoRoot."
            }
            val compose = File(repoRoot, "server/relay/docker-compose.yml").readText()
            val image = Regex("""image:\s*(coturn/coturn:\S+)""").find(compose)
            return requireNotNull(image) { "docker-compose.yml names no coturn image" }.groupValues[1]
        }

        private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

        private const val HEX = "0123456789abcdef"
    }
}
