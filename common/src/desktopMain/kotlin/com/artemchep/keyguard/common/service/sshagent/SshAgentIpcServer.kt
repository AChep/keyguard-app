package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.AgentIpcProtocol
import com.artemchep.keyguard.common.service.agent.AgentIpcServer
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindowNoOp
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path
import java.security.MessageDigest

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

    private val server = AgentIpcServer(
        logRepository = logRepository,
        scope = scope,
        tag = TAG,
        maxConcurrentConnections = maxConcurrentConnections,
        session = { channel ->
            runSshAgentPacketSession(
                channel = AgentIpcProtocol.open(channel),
                rpcHandler = rpcHandler,
                initialContext = SshAgentRpcRequestContext(
                    authenticated = false,
                    allowAuthenticate = true,
                ),
            )
        },
    )

    internal fun stop() = server.stop()

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
    ) = server.start(socketPath, onReady)

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
