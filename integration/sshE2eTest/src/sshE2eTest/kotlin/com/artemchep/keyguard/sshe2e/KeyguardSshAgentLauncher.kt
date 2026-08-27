package com.artemchep.keyguard.sshe2e

import com.artemchep.keyguard.common.service.agent.AGENT_STARTUP_READY_RECORD
import com.artemchep.keyguard.common.service.agent.AGENT_STARTUP_READY_TIMEOUT_MS
import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
import com.artemchep.keyguard.common.service.agent.AgentProcessDiagnosticTail
import com.artemchep.keyguard.common.service.agent.awaitAgentStartupReadiness
import com.artemchep.keyguard.common.service.agent.drainAgentProcessOutput
import com.artemchep.keyguard.common.service.agent.observeProcessExit
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
     * @param sshSocket the endpoint to bind, or null to exercise the platform default.
     */
    fun start(
        ipcEndpoint: AgentIpcEndpoint,
        sshSocket: String? = null,
        environmentOverrides: Map<String, String?> = emptyMap(),
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
                if (sshSocket != null) {
                    add("--ssh-socket")
                    add(sshSocket)
                }
                if (verbose) add("--verbose")
            }
            val builder = ProcessBuilder(command)
            environmentOverrides.forEach { (name, value) ->
                if (value == null) builder.environment().remove(name) else builder.environment()[name] = value
            }
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

    private companion object {
        const val BINARY_NAME = "keyguard-ssh-agent"
    }
}
