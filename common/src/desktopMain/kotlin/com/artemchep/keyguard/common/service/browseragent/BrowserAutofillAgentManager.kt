package com.artemchep.keyguard.common.service.browseragent

import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
import com.artemchep.keyguard.common.service.agent.AgentIpcServer
import com.artemchep.keyguard.common.service.agent.AgentManager
import com.artemchep.keyguard.common.service.agent.cleanupAgentIpcEndpoint
import com.artemchep.keyguard.common.service.agent.createAgentIpcEndpoint
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.util.toHex
import kotlinx.coroutines.*
import java.lang.Process
import java.util.concurrent.TimeUnit

class BrowserAutofillAgentManager(
    logRepository: LogRepository,
    cryptoGenerator: CryptoGenerator,
    private val getVaultSession: GetVaultSession,
    private val backend: BrowserAutofillBackend,
    private val getBrowserAgentPort: () -> Int,
) : AgentManager(
    logRepository = logRepository,
    cryptoGenerator = cryptoGenerator,
    config = Config(
        tag = "BrowserAutofillAgentManager",
        displayName = "Browser autofill agent",
        binaryBaseName = "keyguard-browser-agent",
        ipcSocketPrefix = "keyguard-browser-ipc",
        agentSocketArg = "--ws-port",
        agentSocketLogLabel = "WebSocket port",
        defaultAgentSocketPath = null,
    ),
) {
    private val sessionFileWriter = AgentSessionFileWriter(logRepository)
    private val pairingSecretFileWriter = AgentPairingSecretFileWriter(logRepository)

    @Volatile
    private var wsAgentProcess: Process? = null

    override fun onStop() {
        sessionFileWriter.delete()
    }

    /**
     * Starts both the NM IPC server (for Firefox/Chrome/Edge) and the WS
     * server (for Safari) concurrently. The NM server does not spawn a child
     * process — the browser launches the agent binary via the native
     * messaging manifest. The WS server spawns its own agent process.
     *
     * This is a non-suspend function: servers run as child coroutines of
     * [scope]. When [scope] is cancelled, both servers and their IPC
     * endpoints are cleaned up automatically.
     *
     * @param pairingCode derived into the HMAC shared secret for the WS path.
     */
    fun start(
        scope: CoroutineScope,
        pairingCode: String,
    ) {
        scope.launch(Dispatchers.IO) { startNmServer(scope = this) }
        scope.launch(Dispatchers.IO) { startWsServer(scope = this, pairingCode = pairingCode) }
    }

    /**
     * Starts the IPC server in NM mode WITHOUT spawning a child process.
     *
     * In NM mode, Firefox/Chrome/Edge launches the agent binary via the
     * native messaging manifest. The Kotlin app only needs to:
     * 1. Write the session file (auth token + IPC socket path)
     * 2. Start the IPC server so the browser-spawned agent can connect
     */
    private suspend fun startNmServer(
        scope: CoroutineScope,
    ) {
        val authToken = cryptoGenerator.seed(32)
        val ipcEndpoint = createAgentIpcEndpoint(config.ipcSocketPrefix)

        sessionFileWriter.write(authToken, ipcEndpoint.argument)

        logRepository.post(config.tag, "Starting NM IPC server", LogLevel.INFO)
        logRepository.post(config.tag, "IPC endpoint: ${ipcEndpoint.displayName}", LogLevel.INFO)

        val serverJob = scope.launch(Dispatchers.IO) {
            val server = BrowserAutofillIpcServer(
                logRepository = logRepository,
                authToken = authToken,
                scope = scope,
                backend = backend,
                maxConcurrentConnections = 8,
                tokenOnly = true,
            )
            val serverReady = CompletableDeferred<Unit>()
            launch(Dispatchers.IO) {
                try {
                    server.start(ipcEndpoint, onReady = serverReady)
                } catch (e: Exception) {
                    serverReady.completeExceptionally(e)
                }
            }
            try {
                withTimeout(5_000L) {
                    serverReady.await()
                }
                logRepository.post(config.tag, "NM IPC server is ready", LogLevel.INFO)
            } catch (e: Exception) {
                logRepository.post(config.tag, "NM IPC server failed: ${e.message}", LogLevel.ERROR)
                throw e
            }
        }

        try {
            // Keep the server running until the scope is cancelled.
            serverJob.join()
        } finally {
            withContext(NonCancellable) {
                try {
                    cleanupAgentIpcEndpoint(ipcEndpoint)
                } catch (e: Exception) {
                    logRepository.post(config.tag, "Error cleaning up NM IPC endpoint: ${e.message}", LogLevel.ERROR)
                }
            }
        }
    }

    /**
     * Starts the WebSocket (Safari) stack: an IPC server plus a spawned agent
     * process that listens on `127.0.0.1:<port>`.
     *
     * The agent verifies extension connections with HMAC challenge-response
     * using the shared secret derived from [pairingCode] (written to a
     * 0600-protected file passed via `--secret-path`).
     */
    private suspend fun startWsServer(
        scope: CoroutineScope,
        pairingCode: String,
    ) {
        val binaryPath = defaultBinaryPath
        if (binaryPath == null) {
            logRepository.post(config.tag, "WS agent binary not found", LogLevel.ERROR)
            return
        }

        val authToken = cryptoGenerator.seed(32)
        val ipcEndpoint = createAgentIpcEndpoint(config.ipcSocketPrefix + "-ws")

        pairingSecretFileWriter.write(AgentPairingSecretFileWriter.deriveSharedSecret(pairingCode))

        logRepository.post(config.tag, "Starting WS IPC server", LogLevel.INFO)
        logRepository.post(config.tag, "IPC endpoint: ${ipcEndpoint.displayName}", LogLevel.INFO)

        val serverJob = scope.launch(Dispatchers.IO) {
            val server = BrowserAutofillIpcServer(
                logRepository = logRepository,
                authToken = authToken,
                scope = scope,
                backend = backend,
                maxConcurrentConnections = 8,
                tokenOnly = true,
            )
            val serverReady = CompletableDeferred<Unit>()
            launch(Dispatchers.IO) {
                try {
                    server.start(ipcEndpoint, onReady = serverReady)
                } catch (e: Exception) {
                    serverReady.completeExceptionally(e)
                }
            }
            try {
                withTimeout(5_000L) {
                    serverReady.await()
                }
                logRepository.post(config.tag, "WS IPC server is ready", LogLevel.INFO)
            } catch (e: Exception) {
                logRepository.post(config.tag, "WS IPC server failed: ${e.message}", LogLevel.ERROR)
                throw e
            }
        }

        var myProcess: Process? = null
        try {
            val command = listOf(
                binaryPath.toAbsolutePath().toString(),
                "--ipc-socket", ipcEndpoint.argument,
                "--parent-pid", ProcessHandle.current().pid().toString(),
                "--ws-port", getBrowserAgentPort().toString(),
                "--secret-path", AgentPairingSecretFileWriter.secretPath.toAbsolutePath().toString(),
            )
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT)
            val process = processBuilder.start()
            myProcess = process
            wsAgentProcess = process
            process.outputStream.apply {
                write(authToken.toHex().encodeToByteArray())
                write('\n'.code)
                flush()
            }
            logRepository.post(
                config.tag,
                "WS agent process started (PID: ${process.pid()})",
                LogLevel.INFO,
            )

            scope.launch(Dispatchers.IO) {
                val exitCode = process.waitFor()
                logRepository.post(
                    config.tag,
                    "WS agent process exited with code: $exitCode",
                    LogLevel.INFO,
                )
                if (wsAgentProcess === process) {
                    wsAgentProcess = null
                }
            }
        } catch (e: Exception) {
            logRepository.post(config.tag, "Failed to spawn WS agent: ${e.message}", LogLevel.ERROR)
            pairingSecretFileWriter.delete()
        }

        try {
            serverJob.join()
        } finally {
            withContext(NonCancellable) {
                val owned = myProcess
                if (owned != null) {
                    if (wsAgentProcess === owned) {
                        stopWsAgent()
                    } else {
                        destroyProcessQuietly(owned)
                    }
                }
                try {
                    cleanupAgentIpcEndpoint(ipcEndpoint)
                } catch (e: Exception) {
                    logRepository.post(config.tag, "Error cleaning up WS IPC endpoint: ${e.message}", LogLevel.ERROR)
                }
            }
        }
    }

    private fun destroyProcessQuietly(process: Process) {
        try {
            runCatching { process.outputStream.close() }
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            }
        } catch (e: Exception) {
            logRepository.post(
                config.tag,
                "Error stopping WS agent process: ${e.message}",
                LogLevel.ERROR,
            )
        }
    }

    private fun stopWsAgent() {
        val process = wsAgentProcess
        wsAgentProcess = null
        process?.let(::destroyProcessQuietly)
        pairingSecretFileWriter.delete()
    }

    override fun createIpcServer(
        authToken: ByteArray,
        sessionId: String,
        scope: CoroutineScope,
        expectedPeerProcess: Deferred<Process>,
    ): AgentManager.IpcServerRunner {
        val server = BrowserAutofillIpcServer(
            logRepository = logRepository,
            authToken = authToken,
            scope = scope,
            backend = backend,
            expectedPeerProcess = expectedPeerProcess,
        )
        return IpcServerRunner { endpoint, onReady ->
            server.start(endpoint, onReady)
        }
    }
}
