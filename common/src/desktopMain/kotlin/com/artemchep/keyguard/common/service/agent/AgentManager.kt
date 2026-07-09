package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.build.BuildKonfig
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.seedHex
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/**
 * Manages the lifecycle of an agent Rust binary.
 *
 * Responsibilities:
 * - Generates a cryptographically random authentication token
 * - Creates the IPC Unix domain socket
 * - Starts the IPC server to handle requests from the agent
 * - Spawns the Rust agent binary as a child process
 * - Cleans up on shutdown (kills the process, removes sockets)
 *
 * The pieces that differ between agents (log/error strings, the concrete
 * IPC server construction and any agent-specific request routing) are
 * supplied by subclasses via [config] and [createIpcServer].
 */
abstract class AgentManager(
    protected val logRepository: LogRepository,
    private val cryptoGenerator: CryptoGenerator,
    private val config: Config,
) {
    /**
     * Per-agent constants that are woven into log/error strings and the
     * process/socket wiring.
     *
     * @param tag Log tag, e.g. "SshAgentManager" / "GpgAgentManager".
     * @param displayName Human-readable agent name used in templated log
     *   strings, e.g. "SSH agent" / "GPG agent".
     * @param binaryBaseName Base name of the agent binary (without the
     *   platform-specific extension), e.g. "keyguard-ssh-agent".
     * @param ipcSocketPrefix Prefix for the temporary IPC socket file,
     *   e.g. "keyguard-ipc" / "keyguard-gpg-ipc".
     * @param agentSocketArg CLI flag used to pass the optional agent socket
     *   path to the binary, e.g. "--ssh-socket" / "--gpg-socket".
     * @param agentSocketLogLabel Label used when logging the agent socket
     *   path, e.g. "SSH socket" / "GPG socket".
     * @param defaultAgentSocketPath Optional app-level default socket path.
     *   This is passed to the agent binary unless [start] receives an explicit
     *   override.
     */
    data class Config(
        val tag: String,
        val displayName: String,
        val binaryBaseName: String,
        val ipcSocketPrefix: String,
        val agentSocketArg: String,
        val agentSocketLogLabel: String,
        val defaultAgentSocketPath: Path? = null,
    )

    /**
     * Thin abstraction over an agent's IPC server, allowing the shared
     * lifecycle to start it without knowing its concrete type.
     */
    protected fun interface IpcServerRunner {
        suspend fun start(endpoint: AgentIpcEndpoint, onReady: CompletableDeferred<Unit>?)
    }

    companion object {
        /** Length of the auth token in bytes. */
        private const val AUTH_TOKEN_BYTES = 32

        /** Maximum time (ms) to wait for the IPC server to bind the socket. */
        private const val IPC_SERVER_READY_TIMEOUT_MS = 5_000L
    }

    private val mutex = Mutex()

    private var agentProcess: Process? = null
    private var serverJob: Job? = null
    private var serverScope: CoroutineScope? = null
    private var ipcEndpoint: AgentIpcEndpoint? = null

    val defaultBinaryPath: Path? by lazy {
        findAgentBinary(config.binaryBaseName)
    }

    /**
     * Builds the concrete IPC server for this agent.
     *
     * @param authToken The shared authentication token.
     * @param sessionId The agent session identifier.
     * @param scope The coroutine scope that owns the server.
     */
    protected abstract fun createIpcServer(
        authToken: ByteArray,
        sessionId: String,
        scope: CoroutineScope,
    ): IpcServerRunner

    /**
     * Starts the agent system.
     *
     * This will:
     * 1. Generate a random auth token
     * 2. Start the IPC server on a Unix domain socket
     * 3. Wait for the server to be ready (socket bound)
     * 4. Spawn the Rust agent binary
     *
     * @param scope The coroutine scope for the IPC server.
     * @param binaryPath Path to the agent binary.
     * @param agentSocketPath Optional override for the agent socket path.
     */
    suspend fun start(
        scope: CoroutineScope,
        binaryPath: Path? = null,
        agentSocketPath: Path? = null,
    ): Process = withContext(Dispatchers.IO) {
        mutex.withLock {
            startLocked(
                scope = scope,
                binaryPath = binaryPath,
                agentSocketPath = agentSocketPath,
            )
        }
    }

    private suspend fun startLocked(
        scope: CoroutineScope,
        binaryPath: Path? = null,
        agentSocketPath: Path? = null,
    ): Process {
        val existingProcess = agentProcess
        if (existingProcess?.isAlive == true) {
            logRepository.post(config.tag, "${config.displayName} is already running", LogLevel.INFO)
            return existingProcess
        }
        // The previous agent process is dead or was never started. Fully
        // tear down any leftover state before allocating new resources.
        if (existingProcess != null ||
            serverScope != null ||
            serverJob != null ||
            ipcEndpoint != null
        ) {
            logRepository.post(config.tag, "Cleaning up stale ${config.displayName} state", LogLevel.INFO)
            stopLocked()
        }

        val binaryPath = binaryPath
            ?: this.defaultBinaryPath
        requireNotNull(binaryPath) {
            "Could not find a path to the ${config.binaryBaseName} binary!"
        }

        // Generate cryptographically random auth token.
        val authToken = cryptoGenerator.seed(AUTH_TOKEN_BYTES)
        val authTokenHex = authToken.toHex()
        val agentSessionId = cryptoGenerator.seedHex(length = 16)
        val effectiveAgentSocketPath = agentSocketPath
            ?: config.defaultAgentSocketPath

        // Determine IPC endpoint.
        val ipcEndpoint = createAgentIpcEndpoint(config.ipcSocketPrefix)
        this.ipcEndpoint = ipcEndpoint

        val serverScope = kotlin.run {
            val parentJob = scope.coroutineContext[Job]
            CoroutineScope(scope.coroutineContext + SupervisorJob(parentJob))
        }
        this.serverScope = serverScope

        logRepository.post(config.tag, "Starting ${config.displayName} system", LogLevel.INFO)
        logRepository.post(config.tag, "Binary: $binaryPath", LogLevel.INFO)
        logRepository.post(config.tag, "IPC endpoint: ${ipcEndpoint.displayName}", LogLevel.INFO)
        effectiveAgentSocketPath?.let {
            logRepository.post(config.tag, "${config.agentSocketLogLabel}: $it", LogLevel.INFO)
        }

        // Start the IPC server.
        val ipcServer = createIpcServer(
            authToken = authToken,
            sessionId = agentSessionId,
            scope = serverScope,
        )

        // The server signals readiness via this deferred once the
        // socket is bound and it starts accepting connections.
        val serverReady = CompletableDeferred<Unit>()

        serverJob = serverScope.launch(Dispatchers.IO) {
            try {
                ipcServer.start(ipcEndpoint, onReady = serverReady)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logRepository.post(
                        config.tag,
                        "IPC server failed: ${e.message}\n${e.stackTraceToString()}",
                        LogLevel.ERROR,
                    )
                }
                // If the server fails before signalling readiness,
                // complete exceptionally so we don't hang forever.
                serverReady.completeExceptionally(
                    e.takeUnless { it is CancellationException }
                        ?: IllegalStateException("IPC server was cancelled before becoming ready"),
                )
            }
        }

        // Wait for the IPC server to bind the socket, with a timeout.
        try {
            withTimeout(IPC_SERVER_READY_TIMEOUT_MS) {
                serverReady.await()
            }
            logRepository.post(config.tag, "IPC server is ready", LogLevel.INFO)
        } catch (e: Exception) {
            stopLocked()
            throw e
        }

        // Spawn the Rust agent binary.
        try {
            val command = mutableListOf(
                binaryPath.toAbsolutePath().toString(),
                "--ipc-socket",
                ipcEndpoint.argument,
            )
            effectiveAgentSocketPath?.let {
                command.add(config.agentSocketArg)
                command.add(it.toAbsolutePath().toString())
            }

            val processBuilder = ProcessBuilder(command)
            // Inherit stdout and stderr so we can see the child's logs,
            // but keep stdin as a pipe so we can write the auth token.
            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT)

            val process = processBuilder.start()
            val processStdin = process.outputStream
            agentProcess = process
            // Pass the auth token via stdin -- this avoids exposing the
            // token in the process environment, which is readable by
            // other same-user processes on Linux and macOS. Keep stdin
            // open so the agent can use EOF as a parent-death signal.
            processStdin.write(authTokenHex.encodeToByteArray())
            processStdin.write('\n'.code)
            processStdin.flush()
            logRepository.post(
                config.tag,
                "${config.displayName} process started (PID: ${process.pid()})",
                LogLevel.INFO,
            )

            // Monitor the process in a background coroutine so we
            // log when it exits and clear the stale process reference.
            serverScope.launch(Dispatchers.IO) {
                val exitCode = process.waitFor()
                logRepository.post(
                    config.tag,
                    "${config.displayName} process exited with code: $exitCode",
                    LogLevel.INFO,
                )
                mutex.withLock {
                    if (agentProcess === process) {
                        process.closeStdinQuietly()
                        agentProcess = null
                    }
                }
            }
            return process
        } catch (e: Exception) {
            stopLocked()
            throw e
        }
    }

    /**
     * Suspends until [request] is completed or its [AgentRequest.expiresAt]
     * deadline is reached, whichever comes first.
     */
    protected suspend fun awaitWithExpiry(
        request: AgentRequest,
        reason: String,
    ): Boolean = withTimeoutOrNull(request.expiresAt - Clock.System.now()) {
        request.deferred.await()
    } ?: run {
        request.completeWithLog(
            value = false,
            reason = reason,
        )
        false
    }

    /**
     * Stops the agent and cleans up resources.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.withLock {
            stopLocked()
        }
    }

    /**
     * Internal stop implementation that assumes
     * the [mutex] is already held.
     */
    private fun stopLocked() {
        logRepository.post(config.tag, "Stopping ${config.displayName} system", LogLevel.INFO)

        val process = agentProcess
        agentProcess = null

        // Kill the agent process.
        process?.let {
            try {
                it.closeStdinQuietly()
                // Give the agent a moment to observe stdin EOF and remove its socket.
                if (!it.waitFor(3, TimeUnit.SECONDS)) {
                    it.destroy()
                    if (!it.waitFor(3, TimeUnit.SECONDS)) {
                        it.destroyForcibly()
                    }
                }
            } catch (e: Exception) {
                logRepository.post(
                    config.tag,
                    "Error stopping agent process: ${e.message}",
                    LogLevel.ERROR,
                )
            }
        }

        // Cancel the IPC server.
        serverJob?.cancel()
        serverJob = null
        serverScope?.cancel()
        serverScope = null

        // Explicitly delete the IPC endpoint in case the server's
        // finally block doesn't get a chance to run (e.g. abrupt shutdown).
        ipcEndpoint?.let { endpoint ->
            try {
                cleanupAgentIpcEndpoint(endpoint)
            } catch (e: Exception) {
                logRepository.post(config.tag, "Error deleting IPC endpoint: ${e.message}", LogLevel.ERROR)
            }
        }
        ipcEndpoint = null
    }

    private fun Process.closeStdinQuietly() {
        try {
            outputStream.close()
        } catch (_: Exception) {
        }
    }
}

private const val BUILD_TYPE_DEV = "DEV"

internal fun macosDevAgentSocketPath(relativePath: String): Path? {
    if (BuildKonfig.buildType != BUILD_TYPE_DEV || CurrentPlatform !is Platform.Desktop.MacOS) {
        return null
    }

    val uid = runCatching {
        Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Number
    }.getOrNull() ?: return null

    return Path.of("/tmp", "keyguard-${uid.toLong()}")
        .resolve(relativePath)
}
