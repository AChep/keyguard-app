package com.artemchep.keyguard.integration.webdav

import com.artemchep.keyguard.util.webdav.KtorWebDavClient
import com.artemchep.keyguard.util.webdav.WebDavAuthorization
import com.artemchep.keyguard.util.webdav.WebDavClientConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class HacdiasWebDavServer private constructor(
    val root: Path,
    val baseUrl: String,
    private val process: Process,
    private val stdout: ProcessOutput,
    private val stderr: ProcessOutput,
    private val tempDir: Path,
) : AutoCloseable {
    fun client(
        username: String? = "admin",
        password: String? = "admin",
    ): KtorWebDavClient = KtorWebDavClient(
        httpClient = HttpClient(CIO),
        config = WebDavClientConfig(
            baseUrl = baseUrl,
            authorization = if (username != null && password != null) {
                WebDavAuthorization.Basic(username, password)
            } else {
                null
            },
            userAgent = "KeyguardWebDavE2eTest",
        ),
        closeHttpClient = true,
    )

    fun diagnostics(): String = buildString {
        appendLine("webdav baseUrl: $baseUrl")
        appendLine("webdav root: $root")
        appendLine("webdav alive: ${process.isAlive}")
        appendLine("webdav exit: ${runCatching { process.exitValue() }.getOrNull()}")
        appendLine("webdav stdout:")
        appendLine(stdout.text())
        appendLine("webdav stderr:")
        appendLine(stderr.text())
    }

    override fun close() {
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
        stdout.join()
        stderr.join()
        deleteRecursively(tempDir)
    }

    companion object {
        fun start(
            config: ServerConfig = ServerConfig(),
        ): HacdiasWebDavServer {
            val tempDir = Files.createTempDirectory("keyguard-webdav-e2e-")
            val root = Files.createDirectories(tempDir.resolve("data"))
            val port = freePort()
            val prefix = normalizePrefix(config.prefix)
            val configFile = tempDir.resolve("config.yml")
            Files.writeString(
                configFile,
                config.toYaml(
                    port = port,
                    root = root,
                    prefix = prefix,
                ),
            )

            val process = ProcessBuilder("webdav", "--config", configFile.toString())
                .directory(tempDir.toFile())
                .start()
            val stdout = ProcessOutput(process.inputStream, "webdav-e2e-stdout")
            val stderr = ProcessOutput(process.errorStream, "webdav-e2e-stderr")
            val server = HacdiasWebDavServer(
                root = root,
                baseUrl = "http://127.0.0.1:$port$prefix",
                process = process,
                stdout = stdout,
                stderr = stderr,
                tempDir = tempDir,
            )
            try {
                server.waitUntilReady()
            } catch (e: Throwable) {
                val diagnostics = server.diagnostics()
                server.close()
                throw AssertionError("webdav server did not become ready.\n$diagnostics", e)
            }
            return server
        }

        fun unusedBaseUrl(): String {
            val port = freePort()
            return "http://127.0.0.1:$port/"
        }

        private fun freePort(): Int = ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            socket.localPort
        }

        private fun normalizePrefix(
            value: String,
        ): String {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) {
                "WebDAV prefix must not be empty."
            }
            val withLeadingSlash = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
            return if (withLeadingSlash.endsWith('/')) withLeadingSlash else "$withLeadingSlash/"
        }

        private fun deleteRecursively(
            path: Path,
        ) {
            if (!Files.exists(path)) {
                return
            }
            Files.walk(path).use { paths ->
                paths
                    .sorted(Comparator.reverseOrder())
                    .forEach(Files::deleteIfExists)
            }
        }
    }

    private fun waitUntilReady() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                error("webdav process exited while starting.")
            }
            try {
                val connection = (URI(baseUrl).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "OPTIONS"
                    connectTimeout = 250
                    readTimeout = 250
                }
                val status = connection.responseCode
                connection.disconnect()
                if (status in 200..499) {
                    return
                }
            } catch (e: Throwable) {
                lastFailure = e
            }
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for webdav readiness.", lastFailure)
    }
}

internal data class ServerConfig(
    val prefix: String = "/",
    val permissions: String = "CRUD",
    val users: List<UserConfig> = listOf(
        UserConfig(
            username = "admin",
            password = "admin",
            permissions = "CRUD",
        ),
        UserConfig(
            username = "reader",
            password = "reader",
            permissions = "R",
        ),
    ),
)

internal data class UserConfig(
    val username: String,
    val password: String,
    val permissions: String? = null,
    val directoryName: String? = null,
    val rules: List<RuleConfig> = emptyList(),
)

internal data class RuleConfig(
    val path: String,
    val permissions: String,
)

private fun ServerConfig.toYaml(
    port: Int,
    root: Path,
    prefix: String,
): String = buildString {
    appendLine("address: 127.0.0.1")
    appendLine("port: $port")
    appendLine("tls: false")
    appendLine("prefix: ${prefix.yamlString()}")
    appendLine("debug: false")
    appendLine("directory: ${root.toString().yamlString()}")
    appendLine("permissions: ${permissions.yamlString()}")
    appendLine("log:")
    appendLine("  format: console")
    appendLine("  colors: false")
    appendLine("  outputs:")
    appendLine("    - stderr")
    if (users.isEmpty()) {
        appendLine("users: []")
    } else {
        appendLine("users:")
        users.forEach { user ->
            appendLine("  - username: ${user.username.yamlString()}")
            appendLine("    password: ${user.password.yamlString()}")
            user.permissions?.let { permissions ->
                appendLine("    permissions: ${permissions.yamlString()}")
            }
            user.directoryName?.let { directoryName ->
                val directory = root.resolve(directoryName)
                Files.createDirectories(directory)
                appendLine("    directory: ${directory.toString().yamlString()}")
            }
            if (user.rules.isNotEmpty()) {
                appendLine("    rules:")
                user.rules.forEach { rule ->
                    appendLine("      - path: ${rule.path.yamlString()}")
                    appendLine("        permissions: ${rule.permissions.yamlString()}")
                }
            }
        }
    }
}

private fun String.yamlString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private class ProcessOutput(
    input: InputStream,
    name: String,
) {
    private val buffer = StringBuilder()
    private val thread = thread(
        start = true,
        isDaemon = true,
        name = name,
    ) {
        try {
            input.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(buffer) {
                        buffer.appendLine(line)
                    }
                }
            }
        } catch (_: IOException) {
            // Process shutdown can close streams before the reader drains them.
        }
    }

    fun text(): String = synchronized(buffer) {
        buffer.toString()
    }

    fun join() {
        thread.join(TimeUnit.SECONDS.toMillis(1))
    }
}
