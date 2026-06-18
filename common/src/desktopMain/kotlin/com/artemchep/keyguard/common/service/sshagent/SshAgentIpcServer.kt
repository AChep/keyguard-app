package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindowNoOp
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import java.io.EOFException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlin.time.Duration

/**
 * IPC server that listens for connections from the keyguard-ssh-agent
 * binary and serves SSH key data from the Keyguard vault.
 *
 * The server uses Unix domain sockets (macOS/Linux) and communicates
 * via length-prefixed protobuf messages.
 *
 * The server handles:
 * - Authentication via shared token
 * - Key listing (returning SSH keys from the vault)
 * - Sign requests (delegating to the vault's private keys)
 */
class SshAgentIpcServer(
    private val logRepository: LogRepository,
    private val authToken: ByteArray,
    private val scope: CoroutineScope,
    private val requestProcessor: SshAgentRequestProcessor,
    private val maxConcurrentConnections: Int = 8,
) {
    companion object {
        private const val TAG = "SshAgentIpcServer"

        const val APPROVAL_TIMEOUT_MS = SshAgentRequestProcessorJvm.APPROVAL_TIMEOUT_MS
    }

    private val rpcHandler = SshAgentRpcHandler(
        requestProcessor = requestProcessor,
        authenticate = { req ->
            val success = MessageDigest.isEqual(authToken, req.token)
            if (!success) {
                val errorMessage = "Authentication failed: token mismatch"
                logRepository.post(TAG, errorMessage, LogLevel.ERROR)
            } else {
                val successMessage = "Authentication successful"
                logRepository.post(TAG, successMessage, LogLevel.INFO)
            }
            success
        },
    )

    constructor(
        logRepository: LogRepository,
        getVaultSession: GetVaultSession,
        getSshAgentApprovalWindow: GetSshAgentApprovalWindow = GetSshAgentApprovalWindowNoOp,
        getSshAgentFilter: GetSshAgentFilter,
        authToken: ByteArray,
        scope: CoroutineScope,
        sessionId: String = "",
        maxConcurrentConnections: Int = 8,
        onApprovalRequest: suspend (
            caller: SshAgentMessages.CallerIdentity?,
            keyName: String,
            keyFingerprint: String,
        ) -> Boolean = { _, _, _ -> true },
        onGetListRequest: suspend (
            caller: SshAgentMessages.CallerIdentity?,
        ) -> Boolean = { _ -> false },
        sshAgentPublicKeyRepository: SshAgentPublicKeyRepository = SshAgentPublicKeyRepositoryEmpty,
    ) : this(
        logRepository = logRepository,
        authToken = authToken,
        scope = scope,
        requestProcessor = SshAgentRequestProcessorJvm(
            logRepository = logRepository,
            getVaultSession = getVaultSession,
            getSshAgentApprovalWindow = getSshAgentApprovalWindow,
            getSshAgentFilter = getSshAgentFilter,
            scope = scope,
            sshAgentPublicKeyRepository = sshAgentPublicKeyRepository,
            sessionId = sessionId,
            onApprovalRequest = onApprovalRequest,
            onGetListRequest = onGetListRequest,
        ),
        maxConcurrentConnections = maxConcurrentConnections,
    )

    private val serverChannelLock = Any()
    private var serverChannelRef: ServerSocketChannel? = null

    internal fun stop() {
        val channel = synchronized(serverChannelLock) {
            serverChannelRef
        }
        try {
            channel?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Starts the IPC server on the given Unix domain socket path.
     *
     * This method blocks (suspends) until the server is stopped or an error occurs.
     *
     * @param socketPath The path to the Unix domain socket.
     * @param onReady An optional [CompletableDeferred] that will be completed once the server
     *   has bound the socket and is ready to accept connections. This allows callers to
     *   reliably wait for the server to be ready before spawning the SSH agent binary.
     */
    suspend fun start(
        socketPath: Path,
        onReady: CompletableDeferred<Unit>? = null,
    ) {
        val osName = System.getProperty("os.name")
        if (osName.startsWith("Windows", ignoreCase = true)) {
            val msg = "SSH agent IPC server requires Unix domain sockets; " +
                    "Windows IPC is not implemented yet."
            throw UnsupportedOperationException(msg)
        }

        // Clean up stale socket file.
        Files.deleteIfExists(socketPath)

        // Ensure parent directory exists.
        socketPath.parent?.let { Files.createDirectories(it) }

        val address = UnixDomainSocketAddress.of(socketPath)
        val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        synchronized(serverChannelLock) {
            serverChannelRef = serverChannel
        }
        serverChannel.bind(address)
        val cancellationHandler = currentCoroutineContext()[Job]
            ?.invokeOnCompletion { stop() }

        // Restrict socket permissions to owner-only (0600) to prevent
        // other local users from connecting to the IPC socket.
        try {
            Files.setPosixFilePermissions(
                socketPath,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows) — skip.
            // Windows does not use Unix domain socket file permissions
            // for access control.
        }

        // Signal that the server is ready to accept connections.
        onReady?.complete(Unit)

        val connectionSemaphore = Semaphore(maxConcurrentConnections)

        try {
            logRepository.post(TAG, "IPC server listening on $socketPath", LogLevel.INFO)

            while (scope.isActive && currentCoroutineContext().isActive) {
                val clientChannel = try {
                    withContext(Dispatchers.IO) {
                        serverChannel.accept()
                    }
                } catch (_: AsynchronousCloseException) {
                    break
                } catch (_: ClosedChannelException) {
                    break
                }
                if (!connectionSemaphore.tryAcquire()) {
                    val errorMessage = "Connection rejected: too many concurrent IPC connections " +
                            "(limit=$maxConcurrentConnections)"
                    logRepository.post(TAG, errorMessage, LogLevel.ERROR)

                    try {
                        clientChannel.close()
                    } catch (_: Exception) {
                    }
                    continue
                }
                // Handle each connection in a separate coroutine.
                scope.launch(Dispatchers.IO) {
                    try {
                        handleConnection(clientChannel)
                    } finally {
                        connectionSemaphore.release()
                    }
                }
            }
        } finally {
            cancellationHandler?.dispose()
            stop()
            Files.deleteIfExists(socketPath)
            synchronized(serverChannelLock) {
                serverChannelRef = null
            }
        }
    }

    /**
     * Handles a single client connection from the Rust SSH agent.
     */
    private suspend fun handleConnection(channel: SocketChannel) {
        try {
            runSshAgentPacketSession(
                channel = SshAgentIpcProtocol.open(channel),
                rpcHandler = rpcHandler,
                initialContext = SshAgentRpcRequestContext(
                    authenticated = false,
                    allowAuthenticate = true,
                ),
            )
        } catch (_: AsynchronousCloseException) {
            // Normal during server shutdown.
        } catch (_: ClosedChannelException) {
            // Normal during server shutdown.
        } catch (_: EOFException) {
            logRepository.post(TAG, "Client disconnected", LogLevel.INFO)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                val errorMessage = "Error handling IPC connection: ${e.message}"
                logRepository.post(TAG, errorMessage, LogLevel.ERROR)
            }
        } finally {
            try {
                channel.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Processes a single IPC request and returns the corresponding response.
     */
    internal suspend fun processRequest(
        request: SshAgentMessages.IpcRequest,
        authenticated: Boolean,
    ): SshAgentMessages.IpcResponse = rpcHandler
        .processRequest(
            request = request,
            context = SshAgentRpcRequestContext(
                authenticated = authenticated,
                allowAuthenticate = true,
            ),
        )

    /**
     * Handles the authentication handshake.
     */
    internal fun handleAuthenticate(
        requestId: Long,
        req: SshAgentMessages.AuthenticateRequest,
    ): SshAgentMessages.IpcResponse = rpcHandler
        .handleAuthenticate(requestId, req)

    /**
     * Handles a request to list available SSH keys from the vault.
     */
    internal suspend fun handleListKeys(
        requestId: Long,
        req: SshAgentMessages.ListKeysRequest,
    ): SshAgentMessages.IpcResponse = rpcHandler
        .handleListKeys(requestId, req)

    /**
     * Handles a request to sign data with a specific SSH key by
     * delegating to the shared request processor.
     */
    internal suspend fun handleSignData(
        requestId: Long,
        req: SshAgentMessages.SignDataRequest,
    ): SshAgentMessages.IpcResponse = rpcHandler
        .handleSignData(requestId, req)
}
