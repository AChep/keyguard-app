package com.artemchep.keyguard.gpge2e

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
    private var ipcSocketPath: Path? = null

    /**
     * @param ipcSocketPath short unix socket path for the Kotlin <-> Rust IPC channel.
     * @param gpgSocketPath the `S.gpg-agent` socket the Rust binary binds for gpg to talk to.
     */
    fun start(
        ipcSocketPath: Path,
        gpgSocketPath: Path,
    ) {
        this.ipcSocketPath = ipcSocketPath

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
                server.start(ipcSocketPath, onReady = onReady)
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
            add("--ipc-socket"); add(ipcSocketPath.toAbsolutePath().toString())
            add("--gpg-socket"); add(gpgSocketPath.toAbsolutePath().toString())
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
            waitForSocket(gpgSocketPath, proc)
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    private fun waitForSocket(
        gpgSocketPath: Path,
        proc: Process,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                error("keyguard-gpg-agent exited early with code ${proc.exitValue()}")
            }
            if (Files.exists(gpgSocketPath) && canConnectToSocket(gpgSocketPath)) {
                Thread.sleep(250)
                if (!proc.isAlive) {
                    error("keyguard-gpg-agent exited after binding $gpgSocketPath with code ${proc.exitValue()}")
                }
                if (!Files.exists(gpgSocketPath)) {
                    error("keyguard-gpg-agent socket disappeared after binding: $gpgSocketPath")
                }
                return
            }
            Thread.sleep(50)
        }
        error("Timed out waiting for the gpg socket to appear at $gpgSocketPath")
    }

    private fun canConnectToSocket(gpgSocketPath: Path): Boolean = runCatching {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(gpgSocketPath))
            val command = ByteBuffer.wrap("BYE\n".encodeToByteArray())
            while (command.hasRemaining()) {
                channel.write(command)
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
            out.toByteArray()
                .decodeToString()
                .lineSequence()
                .any { it == "OK" || it.startsWith("OK ") }
        }
    }.getOrDefault(false)

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
        ipcSocketPath?.let { runCatching { Files.deleteIfExists(it) } }
        ipcSocketPath = null
    }

    private fun invokeStop(server: GpgAgentIpcServer) {
        // GpgAgentIpcServer.stop() is internal; reach it reflectively from this module.
        val method = GpgAgentIpcServer::class.java.getDeclaredMethod("stop")
        method.isAccessible = true
        method.invoke(server)
    }
}
