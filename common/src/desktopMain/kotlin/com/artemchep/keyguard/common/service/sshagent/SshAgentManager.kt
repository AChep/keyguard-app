package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.util.flow.EventFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the lifecycle of the keyguard-ssh-agent Rust binary.
 *
 * Responsibilities:
 * - Generates a cryptographically random authentication token
 * - Creates the IPC Unix domain socket
 * - Starts the IPC server to handle requests from the agent
 * - Spawns the Rust SSH agent binary as a child process
 * - Routes sign-request approval prompts to the UI via [approvalRequests]
 * - Cleans up on shutdown (kills the process, removes sockets)
 */
class SshAgentManager(
    private val logRepository: LogRepository,
    private val cryptoGenerator: CryptoGenerator,
    private val getVaultSession: GetVaultSession,
    private val getSshAgentFilter: GetSshAgentFilter,
) {
    companion object {
        private const val TAG = "SshAgentManager"

        /** Length of the auth token in bytes. */
        private const val AUTH_TOKEN_BYTES = 32

        /** Maximum time (ms) to wait for the IPC server to bind the socket. */
        private const val IPC_SERVER_READY_TIMEOUT_MS = 5_000L
    }

    private val mutex = Mutex()

    private var agentProcess: Process? = null
    private var serverJob: Job? = null
    private var serverScope: CoroutineScope? = null
    private var ipcSocketPath: Path? = null

    /**
     * Flow of pending approval requests from the SSH agent.
     *
     * The Compose UI should collect this flow and show an approval
     * dialog for each emitted [SshAgentApprovalRequest]. Completing
     * the request's [CompletableDeferred] with `true` approves the
     * signing; `false` (or letting it time out) denies it.
     */
    val approvalRequests = EventFlow<SshAgentApprovalRequest>()

    /**
     * Flow of pending list-key requests from the SSH agent.
     *
     * Emitted when an SSH agent list-key operation arrives while the
     * vault is locked. The Compose UI should collect this flow and show
     * an unlock prompt. Completing the request's
     * [CompletableDeferred] with `true` indicates the vault was
     * unlocked; `false` (or timeout) indicates failure.
     *
     * Multiple concurrent list-key requests that need the vault unlocked
     * are coalesced into a single request — only one window
     * is shown at a time.
     */
    val getListRequests = EventFlow<SshAgentGetListRequest>()

    val requestsFlow = merge(
        getListRequests,
        approvalRequests,
    )

    val defaultBinaryPath by lazy {
        findSshAgentBinary()
    }

    /**
     * Mutex protecting the [pendingGetListRequest] for coalescing.
     */
    private val unlockMutex = Mutex()

    /**
     * The currently active list-key request, if an unlock prompt is
     * already being shown. Multiple list-key requests that arrive
     * while the vault is locked share this single request.
     */
    private var pendingGetListRequest: SshAgentGetListRequest? = null

    /**
     * Starts the SSH agent system.
     *
     * This will:
     * 1. Generate a random auth token
     * 2. Start the IPC server on a Unix domain socket
     * 3. Wait for the server to be ready (socket bound)
     * 4. Spawn the Rust SSH agent binary
     *
     * @param scope The coroutine scope for the IPC server.
     * @param binaryPath Path to the keyguard-ssh-agent binary.
     * @param sshSocketPath Optional override for the SSH agent socket path.
     */
    suspend fun start(
        scope: CoroutineScope,
        binaryPath: Path? = null,
        sshSocketPath: Path? = null,
    ): Process = withContext(Dispatchers.IO) {
        mutex.withLock {
            startLocked(
                scope = scope,
                binaryPath = binaryPath,
                sshSocketPath = sshSocketPath,
            )
        }
    }

    private suspend fun startLocked(
        scope: CoroutineScope,
        binaryPath: Path? = null,
        sshSocketPath: Path? = null,
    ): Process {
        val existingProcess = agentProcess
        if (existingProcess?.isAlive == true) {
            logRepository.post(TAG, "SSH agent is already running", LogLevel.INFO)
            return existingProcess
        }
        if (existingProcess != null) {
            logRepository.post(TAG, "Cleaning up stale SSH agent process reference", LogLevel.INFO)
            agentProcess = null
        }

        val binaryPath = binaryPath
            ?: this.defaultBinaryPath
        requireNotNull(binaryPath) {
            "Could not find a path to the keyguard-ssh-agent binary!"
        }

        // Generate cryptographically random auth token.
        val authToken = cryptoGenerator.seed(AUTH_TOKEN_BYTES)
        val authTokenHex = authToken.joinToString("") { "%02x".format(it) }

        // Determine IPC socket path (temporary file).
        val ipcSocketPath = createTempSocketPath("keyguard-ipc")
        this.ipcSocketPath = ipcSocketPath

        val serverScope = kotlin.run {
            val parentJob = scope.coroutineContext[Job]
            CoroutineScope(scope.coroutineContext + SupervisorJob(parentJob))
        }
        this.serverScope = serverScope

        logRepository.post(TAG, "Starting SSH agent system", LogLevel.INFO)
        logRepository.post(TAG, "Binary: $binaryPath", LogLevel.INFO)
        logRepository.post(TAG, "IPC socket: $ipcSocketPath", LogLevel.INFO)
        sshSocketPath?.let { logRepository.post(TAG, "SSH socket: $it", LogLevel.INFO) }

        // Start the IPC server.
        val ipcServer = SshAgentIpcServer(
            logRepository = logRepository,
            getVaultSession = getVaultSession,
            getSshAgentFilter = getSshAgentFilter,
            authToken = authToken,
            scope = serverScope,
            onApprovalRequest = { caller, keyName, keyFingerprint ->
                val deferred = CompletableDeferred<Boolean>()
                val request = SshAgentApprovalRequest(
                    keyName = keyName,
                    keyFingerprint = keyFingerprint,
                    caller = caller,
                    timeout = SshAgentIpcServer.APPROVAL_TIMEOUT_MS.milliseconds,
                    deferred = deferred,
                )
                approvalRequests.emit(request)
                deferred.await()
            },
            onGetListRequest = {
                requestGetList(it)
            },
        )

        // The server signals readiness via this deferred once the
        // socket is bound and it starts accepting connections.
        val serverReady = CompletableDeferred<Unit>()

        serverJob = serverScope.launch(Dispatchers.IO) {
            try {
                ipcServer.start(ipcSocketPath, onReady = serverReady)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logRepository.post(
                        TAG,
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
            logRepository.post(TAG, "IPC server is ready", LogLevel.INFO)
        } catch (e: Exception) {
            stopLocked()
            throw e
        }

        // Spawn the Rust SSH agent binary.
        try {
            val command = mutableListOf(
                binaryPath.toAbsolutePath().toString(),
                "--ipc-socket",
                ipcSocketPath.toAbsolutePath().toString(),
            )
            sshSocketPath?.let {
                command.add("--ssh-socket")
                command.add(it.toAbsolutePath().toString())
            }

            val processBuilder = ProcessBuilder(command)
            // Inherit stdout and stderr so we can see the child's logs,
            // but keep stdin as a pipe so we can write the auth token.
            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT)

            val process = processBuilder.start()
            // Pass the auth token via stdin -- this avoids exposing the
            // token in the process environment, which is readable by
            // other same-user processes on Linux and macOS.
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(authTokenHex)
                writer.newLine()
            }
            agentProcess = process
            logRepository.post(
                TAG,
                "SSH agent process started (PID: ${process.pid()})",
                LogLevel.INFO,
            )

            // Monitor the process in a background coroutine so we
            // log when it exits and clear the stale process reference.
            serverScope.launch(Dispatchers.IO) {
                val exitCode = process.waitFor()
                logRepository.post(
                    TAG,
                    "SSH agent process exited with code: $exitCode",
                    LogLevel.INFO,
                )
                mutex.withLock {
                    if (agentProcess === process) {
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
     * Requests the user to unlock the vault for a list-key operation.
     *
     * This method coalesces multiple concurrent list-key requests:
     * if an unlock prompt is already being shown, callers share
     * the existing deferred instead of spawning a new window.
     *
     * @return `true` if the vault was successfully unlocked,
     *   `false` if the user dismissed or the timeout expired.
     */
    private suspend fun requestGetList(
        caller: SshAgentMessages.CallerIdentity?,
    ): Boolean {
        val request = unlockMutex.withLock {
            // If a list-key unlock is already in progress,
            // share the deferred.
            pendingGetListRequest?.let { existing ->
                return@withLock existing
            }

            val deferred = CompletableDeferred<Boolean>()
            val request = SshAgentGetListRequest(
                caller = caller,
                timeout = SshAgentIpcServer.APPROVAL_TIMEOUT_MS.milliseconds,
                deferred = deferred,
            )
            pendingGetListRequest = request
            getListRequests.emit(request)
            request
        }
        return try {
            withTimeoutOrNull(request.timeout.inWholeMilliseconds) {
                request.deferred.await()
            } ?: run {
                request.deferred.complete(false)
                false
            }
        } finally {
            unlockMutex.withLock {
                if (pendingGetListRequest === request) {
                    pendingGetListRequest = null
                }
            }
        }
    }

    /**
     * Stops the SSH agent and cleans up resources.
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
        logRepository.post(TAG, "Stopping SSH agent system", LogLevel.INFO)

        // Kill the agent process.
        agentProcess?.let { process ->
            try {
                process.destroy()
                // Give it a moment to exit gracefully.
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            } catch (e: Exception) {
                logRepository.post(
                    TAG,
                    "Error stopping agent process: ${e.message}",
                    LogLevel.ERROR,
                )
            }
        }
        agentProcess = null

        // Cancel the IPC server.
        serverJob?.cancel()
        serverJob = null
        serverScope?.cancel()
        serverScope = null

        // Explicitly delete the IPC socket file in case the server's
        // finally block doesn't get a chance to run (e.g. abrupt shutdown).
        ipcSocketPath?.let { path ->
            try {
                Files.deleteIfExists(path)
            } catch (e: Exception) {
                logRepository.post(TAG, "Error deleting IPC socket: ${e.message}", LogLevel.ERROR)
            }
        }
        ipcSocketPath = null
    }

    /**
     * Creates a temporary path for a Unix domain socket.
     *
     * Note: We create a path in the system temp directory but don't create
     * the file itself -- the socket server will create it when binding.
     */
    private fun createTempSocketPath(prefix: String): Path {
        val tempDir = Path.of(System.getProperty("java.io.tmpdir"))
        val randomHex = ByteArray(16)
            .also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val socketName = "$prefix-$randomHex.sock"
        return tempDir.resolve(socketName)
    }
}
