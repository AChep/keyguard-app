package com.artemchep.keyguard.sshe2e

import com.artemchep.keyguard.common.service.agent.AGENT_STARTUP_READY_RECORD
import com.artemchep.keyguard.common.service.agent.AGENT_STARTUP_READY_TIMEOUT_MS
import com.artemchep.keyguard.common.service.agent.AgentProcessDiagnosticTail
import com.artemchep.keyguard.common.service.agent.awaitAgentStartupReadiness
import com.artemchep.keyguard.common.service.agent.drainAgentProcessOutput
import com.artemchep.keyguard.common.service.agent.observeProcessExit
import com.artemchep.keyguard.common.service.sshagent.SshAgentIpcServer
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor
import com.artemchep.keyguard.util.agentipc.AgentIpcExpectedPeer
import com.artemchep.keyguard.util.agentipc.AgentIpcLocation
import com.artemchep.keyguard.util.agentipc.asAgentIpcExpectedPeer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.OutputStream
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

    /**
     * @param ipcLocation where to create the endpoint of the Kotlin <-> Rust IPC channel.
     * @param sshSocket the endpoint to bind, or null to exercise the platform default.
     */
    fun start(
        ipcLocation: AgentIpcLocation,
        sshSocket: String? = null,
        environmentOverrides: Map<String, String?> = emptyMap(),
    ) {
        val server = SshAgentIpcServer(
            logRepository = TestLogRepository(),
            authToken = authToken,
            requestProcessor = processor,
        )
        this.ipcServer = server

        try {
            // The endpoint exists before the agent starts; the server accepts
            // nothing until it knows the agent's process.
            val ipcEndpoint = server.bind(ipcLocation)
            val expectedPeer = CompletableDeferred<AgentIpcExpectedPeer>()
            serverJob = scope.launch {
                server.serve { expectedPeer.await() }
            }
            val proc = launchProcess(ipcEndpoint, sshSocket, environmentOverrides)
            process = proc
            expectedPeer.complete(proc.asAgentIpcExpectedPeer())
            initializeProcess(proc)
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    private fun launchProcess(
        ipcEndpoint: String,
        sshSocket: String?,
        environmentOverrides: Map<String, String?>,
    ): Process {
        val verbose = System.getProperty("keyguard.sshE2e.verbose") == "true"
        val command = buildList {
            add(binaryPath.toAbsolutePath().toString())
            addAll(listOf("--ipc-socket", ipcEndpoint))
            addAll(listOf("--parent-pid", ProcessHandle.current().pid().toString()))
            if (sshSocket != null) addAll(listOf("--ssh-socket", sshSocket))
            if (verbose) add("--verbose")
        }
        return ProcessBuilder(command).apply {
            environmentOverrides.forEach { (name, value) ->
                if (value == null) environment().remove(name) else environment()[name] = value
            }
        }.start()
    }

    private fun initializeProcess(
        proc: Process,
    ) {
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
        val procStdin = proc.outputStream.also { processStdin = it }
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
        diagnostics.close()
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
        // Closing also removes the endpoint.
        ipcServer?.close()
        ipcServer = null
        serverJob?.cancel()
        serverJob = null
        scope.cancel()
    }

    private companion object {
        const val BINARY_NAME = "keyguard-ssh-agent"
    }
}
