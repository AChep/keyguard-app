package com.artemchep.keyguard.gpge2e

import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentIpcServer
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class KeyguardAgentLauncher(
    private val binaryPath: Path,
    private val processor: GpgAgentRequestProcessor,
    private val authToken: ByteArray,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var ipcServer: GpgAgentIpcServer? = null
    private var process: Process? = null
    private var processStdin: OutputStream? = null
    private var ipcEndpoint: AgentIpcEndpoint? = null

    /**
     * @param ipcEndpoint endpoint for the Kotlin <-> Rust IPC channel.
     * @param gpgSocket the endpoint the Rust binary binds for gpg or raw Assuan clients.
     */
    fun start(
        ipcEndpoint: AgentIpcEndpoint,
        gpgSocket: String,
    ) {
        this.ipcEndpoint = ipcEndpoint

        val log = TestLogRepository()
        val server = GpgAgentIpcServer(
            logRepository = log,
            authToken = authToken,
            scope = scope,
            requestProcessor = processor,
        )
        this.ipcServer = server

        val onReady = CompletableDeferred<Unit>()
        serverJob = scope.launch {
            runCatching {
                server.start(ipcEndpoint, onReady = onReady)
            }.onFailure { e ->
                onReady.completeExceptionally(e)
            }
        }

        runBlocking {
            withTimeout(5_000) { onReady.await() }
        }

        val verbose = System.getProperty("keyguard.gpgE2e.verbose") == "true"
        val command = buildList {
            add(binaryPath.toAbsolutePath().toString())
            add("--ipc-socket")
            add(ipcEndpoint.argument)
            add("--gpg-socket")
            add(gpgSocket)
            if (verbose) add("--verbose")
        }
        val builder = ProcessBuilder(command)
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        val proc = builder.start()
        this.process = proc
        val procStdin = proc.outputStream
        this.processStdin = procStdin

        try {
            // The Rust binary reads the auth token as HEX + '\n' from its stdin.
            // Keep this stream open until stop(): EOF is the agent's parent-death signal.
            val authTokenHex = authToken.joinToString("") { "%02x".format(it) }
            procStdin.write(authTokenHex.encodeToByteArray())
            procStdin.write('\n'.code)
            procStdin.flush()

            // Wait for the Rust binary to bind the gpg socket before any client gpg runs.
            waitForSocket(gpgSocket, proc)
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    private fun waitForSocket(
        gpgSocket: String,
        proc: Process,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                error("keyguard-gpg-agent exited early with code ${proc.exitValue()}")
            }
            if (canConnectToSocket(gpgSocket)) {
                Thread.sleep(250)
                if (!proc.isAlive) {
                    error("keyguard-gpg-agent exited after binding $gpgSocket with code ${proc.exitValue()}")
                }
                return
            }
            Thread.sleep(50)
        }
        error("Timed out waiting for the gpg socket to appear at $gpgSocket")
    }

    private fun canConnectToSocket(gpgSocket: String): Boolean = runCatching {
        assuanTranscript(gpgSocket, listOf("BYE\n"))
            .any { it == "OK" || it.startsWith("OK ") }
    }.getOrDefault(false)

    fun assuanTranscript(
        gpgSocket: String,
        commands: List<String>,
    ): List<String> {
        val bytes = if (isWindowsHost()) {
            windowsLibassuanTranscript(Path.of(gpgSocket), commands)
        } else {
            unixAssuanTranscript(Path.of(gpgSocket), commands)
        }
        return bytes
            .decodeToString()
            .lineSequence()
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun unixAssuanTranscript(
        gpgSocketPath: Path,
        commands: List<String>,
    ): ByteArray {
        if (!Files.exists(gpgSocketPath)) {
            return ByteArray(0)
        }

        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(gpgSocketPath))
            for (command in commands) {
                val buffer = ByteBuffer.wrap(command.encodeToByteArray())
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
            }
            channel.shutdownOutput()

            val out = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(256)
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read < 0) {
                    break
                }
                buffer.flip()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                out.write(bytes)
            }
            return out.toByteArray()
        }
    }

    private fun windowsLibassuanTranscript(
        markerPath: Path,
        commands: List<String>,
    ): ByteArray {
        val marker = readWindowsAssuanMarker(markerPath) ?: return ByteArray(0)
        Socket(InetAddress.getByName("127.0.0.1"), marker.port).use { socket ->
            val output = socket.getOutputStream()
            output.write(marker.nonce)
            for (command in commands) {
                output.write(command.encodeToByteArray())
            }
            output.flush()
            socket.shutdownOutput()
            return socket.getInputStream().readBytes()
        }
    }

    private fun readWindowsAssuanMarker(path: Path): WindowsAssuanMarker? {
        if (!Files.isRegularFile(path)) return null

        val bytes = Files.readAllBytes(path)
        val separator = bytes.indexOf('\n'.code.toByte())
        require(separator > 0) {
            "Invalid Windows Assuan socket marker at $path: missing port separator"
        }
        require(bytes.size == separator + 1 + WINDOWS_ASSUAN_NONCE_SIZE) {
            "Invalid Windows Assuan socket marker at $path: " +
                "expected a $WINDOWS_ASSUAN_NONCE_SIZE-byte nonce"
        }

        val portText = bytes
            .copyOfRange(0, separator)
            .toString(Charsets.US_ASCII)
        val port = portText.toIntOrNull()
        require(port != null && port in 1..65535) {
            "Invalid Windows Assuan socket marker at $path: invalid port '$portText'"
        }
        return WindowsAssuanMarker(
            port = port,
            nonce = bytes.copyOfRange(separator + 1, bytes.size),
        )
    }

    fun stop() {
        val stdin = processStdin
        processStdin = null
        process?.let { proc ->
            runCatching { stdin?.close() ?: proc.outputStream.close() }
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroy()
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            }
        }
        process = null
        ipcServer?.let { runCatching { invokeStop(it) } }
        serverJob?.cancel()
        serverJob = null
        scope.cancel()
        (ipcEndpoint as? AgentIpcEndpoint.UnixSocket)?.let { endpoint ->
            runCatching { Files.deleteIfExists(endpoint.socketPath) }
        }
        ipcEndpoint = null
    }

    private fun invokeStop(server: GpgAgentIpcServer) {
        // GpgAgentIpcServer.stop() is internal; reach it reflectively from this module.
        val method = GpgAgentIpcServer::class.java.getDeclaredMethod("stop")
        method.isAccessible = true
        method.invoke(server)
    }

    private data class WindowsAssuanMarker(
        val port: Int,
        val nonce: ByteArray,
    )

    companion object {
        private const val WINDOWS_ASSUAN_NONCE_SIZE = 16
    }
}
