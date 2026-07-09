package com.artemchep.keyguard.sshe2e

import com.artemchep.keyguard.common.service.sshagent.SshAgentIpcServer
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class KeyguardSshAgentLauncher(
    private val binaryPath: Path,
    private val processor: SshAgentRequestProcessor,
    private val authToken: ByteArray,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var ipcServer: SshAgentIpcServer? = null
    private var process: Process? = null
    private var processStdin: OutputStream? = null
    private var ipcSocketPath: Path? = null

    /**
     * @param ipcSocketPath short Unix socket path for the Kotlin <-> Rust IPC channel.
     * @param sshSocketPath the SSH_AUTH_SOCK socket the Rust binary binds for OpenSSH clients.
     */
    fun start(
        ipcSocketPath: Path,
        sshSocketPath: Path,
    ) {
        this.ipcSocketPath = ipcSocketPath

        val log = TestLogRepository()
        val server = SshAgentIpcServer(
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

        val verbose = System.getProperty("keyguard.sshE2e.verbose") == "true"
        val command = buildList {
            add(binaryPath.toAbsolutePath().toString())
            add("--ipc-socket")
            add(ipcSocketPath.toAbsolutePath().toString())
            add("--ssh-socket")
            add(sshSocketPath.toAbsolutePath().toString())
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
            // The Rust binary reads the auth token as HEX + '\n' from stdin.
            // Keep the stream open until stop(): EOF is the agent's parent-death signal.
            val authTokenHex = authToken.joinToString("") { "%02x".format(it) }
            procStdin.write(authTokenHex.encodeToByteArray())
            procStdin.write('\n'.code)
            procStdin.flush()

            waitForSocket(sshSocketPath, proc)
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    private fun waitForSocket(
        sshSocketPath: Path,
        proc: Process,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                error("keyguard-ssh-agent exited early with code ${proc.exitValue()}")
            }
            if (Files.exists(sshSocketPath) && canConnectToSocket(sshSocketPath)) {
                Thread.sleep(250)
                if (!proc.isAlive) {
                    error("keyguard-ssh-agent exited after binding $sshSocketPath with code ${proc.exitValue()}")
                }
                if (!Files.exists(sshSocketPath)) {
                    error("keyguard-ssh-agent socket disappeared after binding: $sshSocketPath")
                }
                return
            }
            Thread.sleep(50)
        }
        error("Timed out waiting for the SSH socket to appear at $sshSocketPath")
    }

    private fun canConnectToSocket(sshSocketPath: Path): Boolean = runCatching {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(sshSocketPath))
        }
        true
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

    private fun invokeStop(server: SshAgentIpcServer) {
        // SshAgentIpcServer.stop() is internal; reach it reflectively from this module.
        val method = SshAgentIpcServer::class.java.getDeclaredMethod("stop")
        method.isAccessible = true
        method.invoke(server)
    }
}
