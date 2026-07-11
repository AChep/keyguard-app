package com.artemchep.keyguard.sshe2e

import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
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
import java.io.RandomAccessFile
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
    private var ipcEndpoint: AgentIpcEndpoint? = null

    /**
     * @param ipcEndpoint endpoint for the Kotlin <-> Rust IPC channel.
     * @param sshSocket the SSH_AUTH_SOCK endpoint the Rust binary binds for OpenSSH clients.
     */
    fun start(
        ipcEndpoint: AgentIpcEndpoint,
        sshSocket: String,
    ) {
        this.ipcEndpoint = ipcEndpoint

        val log = TestLogRepository()
        val expectedPeerProcess = CompletableDeferred<Process>()
        val server = SshAgentIpcServer(
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

            val verbose = System.getProperty("keyguard.sshE2e.verbose") == "true"
            val command = buildList {
                add(binaryPath.toAbsolutePath().toString())
                add("--ipc-socket")
                add(ipcEndpoint.argument)
                add("--parent-pid")
                add(ProcessHandle.current().pid().toString())
                add("--ssh-socket")
                add(sshSocket)
                if (verbose) add("--verbose")
            }
            val builder = ProcessBuilder(command)
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            builder.redirectError(ProcessBuilder.Redirect.INHERIT)
            val proc = builder.start()
            this.process = proc
            check(expectedPeerProcess.complete(proc)) {
                "Expected SSH IPC peer process was already published"
            }
            val procStdin = proc.outputStream
            this.processStdin = procStdin

            // The Rust binary reads the auth token as HEX + '\n' from stdin.
            // Keep the stream open until stop(): EOF is the agent's parent-death signal.
            val authTokenHex = authToken.joinToString("") { "%02x".format(it) }
            procStdin.write(authTokenHex.encodeToByteArray())
            procStdin.write('\n'.code)
            procStdin.flush()

            waitForSocket(sshSocket, proc)
        } catch (e: Exception) {
            expectedPeerProcess.completeExceptionally(e)
            stop()
            throw e
        }
    }

    private fun waitForSocket(
        sshSocket: String,
        proc: Process,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                error("keyguard-ssh-agent exited early with code ${proc.exitValue()}")
            }
            if (canConnectToSocket(sshSocket)) {
                Thread.sleep(250)
                if (!proc.isAlive) {
                    error("keyguard-ssh-agent exited after binding $sshSocket with code ${proc.exitValue()}")
                }
                return
            }
            Thread.sleep(50)
        }
        error("Timed out waiting for the SSH socket to appear at $sshSocket")
    }

    private fun canConnectToSocket(sshSocket: String): Boolean {
        if (isWindowsPipe(sshSocket)) {
            return runCatching {
                RandomAccessFile(sshSocket, "rw").use {
                    // Connect and close: this is only a readiness probe.
                }
                true
            }.getOrDefault(false)
        }

        val sshSocketPath = Path.of(sshSocket)
        if (!Files.exists(sshSocketPath)) {
            return false
        }
        return runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(sshSocketPath))
            }
            true
        }.getOrDefault(false)
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

    private fun invokeStop(server: SshAgentIpcServer) {
        // SshAgentIpcServer.stop() is internal; reach it reflectively from this module.
        val method = SshAgentIpcServer::class.java.getDeclaredMethod("stop")
        method.isAccessible = true
        method.invoke(server)
    }

    private fun isWindowsPipe(value: String): Boolean =
        value.startsWith("\\\\.\\pipe\\", ignoreCase = true)
}
