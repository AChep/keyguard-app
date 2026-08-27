package com.artemchep.keyguard.gpge2e

import com.artemchep.keyguard.common.service.agent.AGENT_STARTUP_READY_RECORD
import com.artemchep.keyguard.common.service.agent.AGENT_STARTUP_READY_TIMEOUT_MS
import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
import com.artemchep.keyguard.common.service.agent.AgentProcessDiagnosticTail
import com.artemchep.keyguard.common.service.agent.awaitAgentStartupReadiness
import com.artemchep.keyguard.common.service.agent.drainAgentProcessOutput
import com.artemchep.keyguard.common.service.agent.observeProcessExit
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
import java.net.SocketException
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
        val expectedPeerProcess = CompletableDeferred<Process>()
        val server = GpgAgentIpcServer(
            logRepository = log,
            authToken = authToken,
            scope = scope,
            requestProcessor = processor,
            expectedPeerProcess = expectedPeerProcess,
        )
        this.ipcServer = server

        val onReady = CompletableDeferred<Unit>()
        serverJob = scope.launch {
            runCatching {
                server.start(ipcEndpoint, onReady = onReady)
            }.onFailure { e ->
                onReady.completeExceptionally(e)
                expectedPeerProcess.completeExceptionally(e)
            }
        }

        try {
            runBlocking {
                withTimeout(5_000) { onReady.await() }
            }

            val verbose = System.getProperty("keyguard.gpgE2e.verbose") == "true"
            val command = buildList {
                add(binaryPath.toAbsolutePath().toString())
                add("--ipc-socket")
                add(ipcEndpoint.argument)
                add("--parent-pid")
                add(ProcessHandle.current().pid().toString())
                add("--gpg-socket")
                add(gpgSocket)
                if (verbose) add("--verbose")
            }
            val builder = ProcessBuilder(command)
            val proc = builder.start()
            this.process = proc
            val startupReady = CompletableDeferred<Unit>()
            val diagnostics = AgentProcessDiagnosticTail()
            val outputDrains = drainAgentProcessOutput(
                scope = scope,
                process = proc,
                displayName = BINARY_NAME,
                readyRecord = AGENT_STARTUP_READY_RECORD,
                ready = startupReady,
                diagnostics = diagnostics,
                logStdout = { line -> println("$BINARY_NAME stdout: $line") },
                logStderr = { line -> System.err.println("$BINARY_NAME stderr: $line") },
                logReadFailure = { message -> System.err.println(message) },
            )
            val processExit = observeProcessExit(proc)
            check(expectedPeerProcess.complete(proc)) {
                "Expected GPG IPC peer process was already published"
            }
            val procStdin = proc.outputStream
            this.processStdin = procStdin

            // The Rust binary reads the auth token as HEX + '\n' from its stdin.
            // Keep this stream open until stop(): EOF is the agent's parent-death signal.
            val authTokenHex = authToken.joinToString("") { "%02x".format(it) }
            procStdin.write(authTokenHex.encodeToByteArray())
            procStdin.write('\n'.code)
            procStdin.flush()

            runBlocking {
                awaitAgentStartupReadiness(
                    ready = startupReady,
                    processExited = processExit,
                    timeoutMs = AGENT_STARTUP_READY_TIMEOUT_MS,
                    displayName = BINARY_NAME,
                    diagnostics = diagnostics,
                    outputDrains = outputDrains,
                )
            }
            // The tail is only readable until readiness resolves; stop
            // buffering the child's output for the rest of its lifetime.
            diagnostics.close()
        } catch (e: Exception) {
            expectedPeerProcess.completeExceptionally(e)
            stop()
            throw e
        }
    }

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
            return readWindowsAssuanResponse(socket)
        }
    }

    private fun readWindowsAssuanResponse(socket: Socket): ByteArray {
        val input = socket.getInputStream()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
        } catch (e: SocketException) {
            // A malformed Assuan request may cause the Rust server to close
            // while bytes sent after the protocol error are still unread. On
            // Windows loopback TCP this can surface as a reset rather than
            // EOF. Preserve the response bytes already received; the caller's
            // protocol assertions still reject a truncated response.
            if (!e.isExpectedConnectionReset()) throw e
        }
        return out.toByteArray()
    }

    private fun SocketException.isExpectedConnectionReset(): Boolean {
        val message = message ?: return false
        return message.contains("connection reset", ignoreCase = true) ||
            message.contains("forcibly closed", ignoreCase = true)
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
        private const val BINARY_NAME = "keyguard-gpg-agent"
        private const val WINDOWS_ASSUAN_NONCE_SIZE = 16
    }
}
