package com.artemchep.keyguard.integration.bitwarden

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.ToStringConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.net.ServerSocket
import java.time.Duration

/**
 * Vaultwarden, API only, configured by `server/bw/vaultwarden.env`. Every
 * container starts with an empty in-memory database.
 */
internal class VaultwardenContainer private constructor(
    /** As requested; `dockerImageName` would resolve the image, which fails without Docker. */
    private val imageName: DockerImageName,
    val port: Int,
) : GenericContainer<VaultwardenContainer>(imageName) {
    private val logs = ToStringConsumer()

    /** Equals the `DOMAIN` that the server builds its URLs from. */
    val baseUrl: String = "http://$LOOPBACK:$port"

    init {
        withExposedPorts(HTTP_PORT)
        // Like `-p 127.0.0.1:<port>:80`. The port is fixed because DOMAIN carries it, and
        // loopback keeps the token-less admin API off other interfaces.
        withCreateContainerCmdModifier { command ->
            command.hostConfig?.withPortBindings(
                PortBinding(Ports.Binding.bindIpAndPort(LOOPBACK, port), ExposedPort.tcp(HTTP_PORT)),
            )
        }
        withLogConsumer(logs)
    }

    /** The length of the log so far; pass it to [diagnostics] to print only what follows. */
    fun logLength(): Int = logs.toUtf8String().length

    fun diagnostics(logsFrom: Int = 0): String = buildString {
        appendLine("vaultwarden image: ${imageName.asCanonicalNameString()}")
        appendLine("vaultwarden base url: $baseUrl")
        appendLine("vaultwarden logs:")
        append(logs.toUtf8String().drop(logsFrom))
    }

    companion object {
        private const val HTTP_PORT = 80
        private const val LOOPBACK = "127.0.0.1"
        private const val IMAGE_KEY = "VAULTWARDEN_IMAGE"
        private const val IMAGE_PROPERTY = "keyguard.vaultwarden.image"
        private val STARTUP_TIMEOUT = Duration.ofSeconds(90)

        /**
         * Starts a server configured by `vaultwarden.env` with [overrides] applied on top,
         * for example `mapOf("SIGNUPS_ALLOWED" to "false")`.
         */
        @Suppress("TooGenericExceptionCaught")
        fun start(overrides: Map<String, String> = emptyMap()): VaultwardenContainer {
            require("DOMAIN" !in overrides) { "DOMAIN carries the host port; it can not be overridden" }
            val settings = loadSettings()
            val image = System.getProperty(IMAGE_PROPERTY)
                ?.takeIf { it.isNotBlank() }
                ?: requireNotNull(settings[IMAGE_KEY]) { "$IMAGE_KEY is missing from vaultwarden.env" }
            val port = freePort()
            val env = settings - IMAGE_KEY + overrides + ("DOMAIN" to "http://$LOOPBACK:$port")
            val container = VaultwardenContainer(DockerImageName.parse(image), port)
                .withEnv(env)
                .withTmpFs(mapOf("/data" to "rw"))
                .waitingFor(
                    Wait.forHttp("/alive")
                        .forPort(HTTP_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(STARTUP_TIMEOUT),
                )
            try {
                container.start()
            } catch (e: Throwable) {
                val diagnostics = container.diagnostics()
                runCatching { container.close() }.exceptionOrNull()?.let(e::addSuppressed)
                throw AssertionError("Vaultwarden did not become ready.\n$diagnostics", e)
            }
            return container
        }

        private fun loadSettings(): Map<String, String> {
            val repoRoot = requireNotNull(System.getProperty("keyguard.repoRoot")) {
                "Run through Gradle, which sets keyguard.repoRoot."
            }
            val file = File(repoRoot, "server/bw/vaultwarden.env")
            return parseEnvFile(file.readText())
        }

        private fun freePort(): Int = ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            socket.localPort
        }
    }
}

/** Parses `KEY=VALUE` lines; blank lines and lines starting with `#` are skipped. */
internal fun parseEnvFile(text: String): Map<String, String> = text
    .lineSequence()
    .map(String::trim)
    .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
    .associate { line ->
        val separator = line.indexOf('=')
        require(separator > 0) { "Expected KEY=VALUE, got '$line'" }
        line.substring(0, separator) to line.substring(separator + 1)
    }
