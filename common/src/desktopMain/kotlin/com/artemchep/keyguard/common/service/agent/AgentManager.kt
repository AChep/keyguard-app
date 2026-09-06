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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/** Exact stdout record emitted when an agent's public endpoint is ready. */
const val AGENT_STARTUP_READY_RECORD = "KEYGUARD_AGENT_READY 1"

/** Maximum time (ms) to wait for [AGENT_STARTUP_READY_RECORD]. */
const val AGENT_STARTUP_READY_TIMEOUT_MS = 10_000L

private const val AGENT_OUTPUT_DRAIN_TIMEOUT_MS = 200L

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
     * @param startupReadyRecord Exact stdout line that the child emits after
     *   its public endpoint is ready.
     * @param startupReadyTimeoutMs Maximum time to wait for
     *   [startupReadyRecord].
     */
    data class Config(
        val tag: String,
        val displayName: String,
        val binaryBaseName: String,
        val ipcSocketPrefix: String,
        val agentSocketArg: String,
        val agentSocketLogLabel: String,
        val defaultAgentSocketPath: Path? = null,
        val startupReadyRecord: String = AGENT_STARTUP_READY_RECORD,
        val startupReadyTimeoutMs: Long = AGENT_STARTUP_READY_TIMEOUT_MS,
    )

    /**
     * Thin abstraction over an agent's IPC server, allowing the shared
     * lifecycle to start it without knowing its concrete type.
     */
    protected interface IpcServerRunner {
        suspend fun start(endpoint: AgentIpcEndpoint, onReady: CompletableDeferred<Unit>?)

        fun stop()
    }

    companion object {
        /** Length of the auth token in bytes. */
        private const val AUTH_TOKEN_BYTES = 32

        /** Maximum time (ms) to wait for the IPC server to bind the socket. */
        private const val IPC_SERVER_READY_TIMEOUT_MS = 5_000L
    }

    private val mutex = Mutex()

    private var agentProcess: Process? = null
    private var ipcServerRunner: IpcServerRunner? = null
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
     * @param expectedPeerProcess Completed with the exact child process after
     *   it is spawned. The IPC transport waits for this before accepting.
     */
    protected abstract fun createIpcServer(
        authToken: ByteArray,
        sessionId: String,
        scope: CoroutineScope,
        expectedPeerProcess: Deferred<Process>,
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
        val hasStaleState = when {
            existingProcess != null -> true
            ipcServerRunner != null -> true
            serverScope != null -> true
            serverJob != null -> true
            ipcEndpoint != null -> true
            else -> false
        }
        if (hasStaleState) {
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

        // Start the IPC server. It binds before the helper is spawned, but its
        // accept loop remains gated on this exact Process handle.
        val expectedPeerProcess = CompletableDeferred<Process>()
        val ipcServer = createIpcServer(
            authToken = authToken,
            sessionId = agentSessionId,
            scope = serverScope,
            expectedPeerProcess = expectedPeerProcess,
        )
        ipcServerRunner = ipcServer

        // The server signals readiness via this deferred once the
        // socket is bound and it starts accepting connections.
        val serverReady = CompletableDeferred<Unit>()

        serverJob = serverScope.launch(Dispatchers.IO) {
            try {
                ipcServer.start(ipcEndpoint, onReady = serverReady)
            } catch (e: Exception) {
                val serverFailure = e.takeUnless { it is CancellationException }
                    ?: IllegalStateException("IPC server was cancelled before the helper was ready")
                if (e !is CancellationException) {
                    logRepository.post(
                        config.tag,
                        "IPC server failed: ${e.message}\n${e.stackTraceToString()}",
                        LogLevel.ERROR,
                    )
                }
                // If the server fails before signalling readiness,
                // complete exceptionally so we don't hang forever.
                serverReady.completeExceptionally(serverFailure)
                // The server may already have signalled readiness and be
                // waiting for the helper Process. Wake that gate as well so a
                // concurrent startup cannot publish a child to a dead server.
                expectedPeerProcess.completeExceptionally(serverFailure)
            }
        }

        // Wait for the IPC server to bind the socket, with a timeout.
        try {
            withTimeout(IPC_SERVER_READY_TIMEOUT_MS) {
                serverReady.await()
            }
            logRepository.post(config.tag, "IPC server is ready", LogLevel.INFO)
        } catch (e: Exception) {
            expectedPeerProcess.completeExceptionally(e)
            stopLocked()
            throw e
        }

        // Spawn the Rust agent binary.
        try {
            val command = mutableListOf(
                binaryPath.toAbsolutePath().toString(),
                "--ipc-socket",
                ipcEndpoint.argument,
                "--parent-pid",
                ProcessHandle.current().pid().toString(),
            )
            effectiveAgentSocketPath?.let {
                command.add(config.agentSocketArg)
                command.add(it.toAbsolutePath().toString())
            }

            // The child's stdout carries the startup readiness record and both
            // streams are retained as bounded startup diagnostics, so they are
            // drained through pipes rather than inherited.
            val process = ProcessBuilder(command).start()
            val startupReady = CompletableDeferred<Unit>()
            val startupDiagnostics = AgentProcessDiagnosticTail()
            val outputDrains = drainAgentProcessOutput(
                scope = serverScope,
                process = process,
                displayName = config.displayName,
                readyRecord = config.startupReadyRecord,
                ready = startupReady,
                diagnostics = startupDiagnostics,
                logStdout = { line ->
                    logRepository.post(
                        config.tag,
                        "${config.displayName} stdout: $line",
                        LogLevel.INFO,
                    )
                },
                logStderr = { line ->
                    logRepository.post(
                        config.tag,
                        "${config.displayName} stderr: $line",
                        LogLevel.INFO,
                    )
                },
                logReadFailure = { message ->
                    logRepository.post(config.tag, message, LogLevel.ERROR)
                },
            )
            val processExit = observeProcessExit(process)
            val processStdin = process.outputStream
            agentProcess = process
            publishExpectedPeerProcess(
                expectedPeerProcess = expectedPeerProcess,
                process = process,
            )
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

            awaitAgentStartupReadiness(
                ready = startupReady,
                processExited = processExit,
                timeoutMs = config.startupReadyTimeoutMs,
                displayName = config.displayName,
                diagnostics = startupDiagnostics,
                outputDrains = outputDrains,
            )
            // The tail is only readable until readiness resolves; stop
            // buffering the child's output for the rest of its lifetime.
            startupDiagnostics.close()
            logRepository.post(
                config.tag,
                "${config.displayName} public endpoint is ready",
                LogLevel.INFO,
            )

            // Monitor the process in a background coroutine so we
            // log when it exits and clear the stale process reference.
            serverScope.launch(Dispatchers.IO) {
                val exitCode = processExit.await()
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
            expectedPeerProcess.completeExceptionally(e)
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

        // Stop the agent process before closing the IPC transport. The agent
        // holds an open IPC connection that our server is blocked reading
        // from. On Windows the pipe handles are synchronous, so closing a
        // handle waits for that pending read, and the read only ends once the
        // agent disconnects, which it does after seeing stdin EOF or dying.
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

        // Closing the transport is what releases a blocking Unix accept() or
        // Windows ConnectNamedPipe() call. Coroutine cancellation alone cannot
        // make progress until that blocking call returns.
        val ipcServerRunner = ipcServerRunner
        this.ipcServerRunner = null
        try {
            ipcServerRunner?.stop()
        } catch (e: Exception) {
            logRepository.post(
                config.tag,
                "Error stopping IPC server: ${e.message}",
                LogLevel.ERROR,
            )
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

/**
 * Drains a readiness-enabled agent process's stdout/stderr, completing
 * [ready] on the exact [readyRecord] line and forwarding everything else for
 * the process lifetime. [diagnostics] retains output only until it is closed.
 * Shared between [AgentManager] and the agent e2e launchers.
 */
fun drainAgentProcessOutput(
    scope: CoroutineScope,
    process: Process,
    displayName: String,
    readyRecord: String,
    ready: CompletableDeferred<Unit>,
    diagnostics: AgentProcessDiagnosticTail,
    logStdout: (line: String) -> Unit,
    logStderr: (line: String) -> Unit,
    logReadFailure: (message: String) -> Unit,
): List<Job> {
    fun drainStream(
        streamName: String,
        lines: java.io.BufferedReader,
        logLine: (line: String) -> Unit,
        consumeLine: (String) -> Boolean = { false },
    ) {
        try {
            lines.use { reader ->
                reader.lineSequence().forEach { line ->
                    // Closing startup diagnostics stops retention, not logging.
                    // The native agent's filter controls which events it emits.
                    if (!consumeLine(line)) {
                        diagnostics.append(streamName, line)
                        logLine(line)
                    }
                }
            }
        } catch (e: IOException) {
            if (process.isAlive) {
                val message = "Failed to read $displayName $streamName: ${e.message}"
                diagnostics.append(streamName, message)
                logReadFailure(message)
            }
        }
    }

    val stdoutDrain = scope.launch(Dispatchers.IO) {
        drainStream(
            streamName = "stdout",
            lines = process.inputStream.bufferedReader(),
            logLine = logStdout,
            consumeLine = { line ->
                (line == readyRecord).also { matched ->
                    if (matched) {
                        ready.complete(Unit)
                    }
                }
            },
        )
    }
    val stderrDrain = scope.launch(Dispatchers.IO) {
        drainStream(
            streamName = "stderr",
            lines = process.errorStream.bufferedReader(),
            logLine = logStderr,
        )
    }
    return listOf(stdoutDrain, stderrDrain)
}

class AgentProcessDiagnosticTail(
    private val maxChars: Int = 8_192,
) {
    private val lines = ArrayDeque<String>()
    private var totalChars = 0
    private var closed = false

    init {
        require(maxChars > 0) { "Diagnostic tail size must be positive" }
    }

    val isClosed: Boolean
        @Synchronized
        get() = closed

    @Synchronized
    fun append(streamName: String, line: String) {
        if (closed) {
            return
        }
        val entry = "[$streamName] $line".takeLast(maxChars)
        // Each retained older entry adds a newline to the final snapshot.
        while (lines.isNotEmpty() && totalChars.toLong() + lines.size + entry.length > maxChars) {
            totalChars -= lines.removeFirst().length
        }
        lines.addLast(entry)
        totalChars += entry.length
    }

    /**
     * Discards the buffered output and stops retaining new lines. The tail is
     * only consumed while startup can still fail; the drain coroutines keep
     * running for the whole process lifetime.
     */
    @Synchronized
    fun close() {
        closed = true
        lines.clear()
        totalChars = 0
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")
}

fun observeProcessExit(
    process: Process,
): Deferred<Int> = CompletableDeferred<Int>().also { exited ->
    process.onExit().whenComplete { completedProcess, error ->
        if (error != null) {
            exited.completeExceptionally(error)
        } else {
            exited.complete(completedProcess.exitValue())
        }
    }
}

suspend fun awaitAgentStartupReadiness(
    ready: Deferred<Unit>,
    processExited: Deferred<Int>,
    timeoutMs: Long,
    displayName: String,
    diagnostics: AgentProcessDiagnosticTail,
    outputDrains: List<Job> = emptyList(),
) {
    val exitCode = try {
        withTimeout(timeoutMs) {
            select<Int?> {
                ready.onAwait { null }
                processExited.onAwait { it }
            }
        }
    } catch (_: TimeoutCancellationException) {
        throw IllegalStateException(
            agentStartupFailureMessage(
                displayName = displayName,
                reason = "did not become ready within ${timeoutMs}ms",
                diagnostics = diagnostics,
            ),
        )
    }

    // The readiness record can win the select against a process that has
    // already died; ready but already exited is still a startup failure.
    val resolvedExitCode = exitCode
        ?: processExited.takeIf { it.isCompleted }?.await()
    if (resolvedExitCode != null) {
        // A process exit can race the pipe readers. Wait until EOF has been
        // consumed so an early-startup failure includes its final diagnostics.
        withTimeoutOrNull(AGENT_OUTPUT_DRAIN_TIMEOUT_MS) {
            outputDrains.joinAll()
        }
        throw IllegalStateException(
            agentStartupFailureMessage(
                displayName = displayName,
                reason = "process exited unexpectedly with code $resolvedExitCode",
                diagnostics = diagnostics,
            ),
        )
    }
}

private fun agentStartupFailureMessage(
    displayName: String,
    reason: String,
    diagnostics: AgentProcessDiagnosticTail,
): String = buildString {
    append(displayName)
    append(' ')
    append(reason)
    diagnostics.snapshot().takeIf { it.isNotEmpty() }?.let { output ->
        append("\nChild process output:\n")
        append(output)
    }
}

internal suspend fun publishExpectedPeerProcess(
    expectedPeerProcess: CompletableDeferred<Process>,
    process: Process,
) {
    if (expectedPeerProcess.complete(process)) {
        return
    }

    // If the IPC server completed the gate exceptionally after signalling
    // readiness, preserve that failure instead of replacing it with a
    // misleading duplicate-publication error.
    expectedPeerProcess.await()
    error("Expected IPC peer process was already published")
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
