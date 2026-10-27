package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.AgentIpcAuthenticator
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.agent.AgentIpcServerHost
import com.artemchep.keyguard.common.service.agent.AgentRpcRequestContext
import com.artemchep.keyguard.common.service.agent.asAgentByteStream
import com.artemchep.keyguard.common.service.agent.checkAndLog
import com.artemchep.keyguard.common.service.agent.serveFramed
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.session.SshAgentSessionAccess
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicyNoOp
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindowNoOp
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.util.agentipc.AgentIpcExpectedPeer
import com.artemchep.keyguard.util.agentipc.AgentIpcLocation
import kotlinx.coroutines.CoroutineScope

/**
 * IPC server that listens for connections from the keyguard-ssh-agent
 * binary and serves SSH key data from the Keyguard vault.
 *
 * The server uses the private endpoint of `util/agent-ipc` and communicates
 * via length-prefixed protobuf messages.
 *
 * The server handles:
 * - Authentication via shared token
 * - Key listing (returning SSH keys from the vault)
 * - Sign requests (delegating to the vault's private keys)
 */
class SshAgentIpcServer(
    private val logRepository: LogRepository,
    authToken: ByteArray,
    requestProcessor: SshAgentRequestProcessor,
    maxConcurrentConnections: Int = 8,
) {
    companion object {
        private const val TAG = "SshAgentIpcServer"
    }

    private val authenticator = AgentIpcAuthenticator(authToken)

    private val rpcHandler = SshAgentRpcHandler(
        // The agent reports the client's OS-level process name and path, so
        // bound and strip them before they reach approvals and usage history.
        requestProcessor = CallerMergingSshAgentRequestProcessor(
            delegate = requestProcessor,
            senderAppInfo = null,
            replaceCallerAuthorization = false,
        ),
        authenticate = { req ->
            authenticator.checkAndLog(req, logRepository, TAG, agentName = "SSH agent")
        },
    )

    constructor(
        logRepository: LogRepository,
        getVaultSession: GetVaultSession,
        sessionAccess: SshAgentSessionAccess,
        getSshAgentApprovalWindow: GetSshAgentApprovalWindow = GetSshAgentApprovalWindowNoOp,
        getSshAgentApprovalCachePolicy: GetSshAgentApprovalCachePolicy =
            GetSshAgentApprovalCachePolicyNoOp,
        getSshAgentFilter: GetSshAgentFilter,
        authToken: ByteArray,
        scope: CoroutineScope,
        sessionId: String = "",
        maxConcurrentConnections: Int = 8,
        onApprovalRequest: suspend (SshAgentApprovalPrompt) -> Boolean = { true },
        pendingUsageHistoryQueue: PendingUsageHistoryQueue? = null,
        sshAgentPublicKeyRepository: SshAgentPublicKeyRepository = SshAgentPublicKeyRepositoryEmpty,
    ) : this(
        logRepository = logRepository,
        authToken = authToken,
        requestProcessor = SshAgentRequestProcessorImpl(
            logRepository = logRepository,
            getVaultSession = getVaultSession,
            sessionAccess = sessionAccess,
            getSshAgentApprovalWindow = getSshAgentApprovalWindow,
            getSshAgentApprovalCachePolicy = getSshAgentApprovalCachePolicy,
            getSshAgentFilter = getSshAgentFilter,
            scope = scope,
            sshAgentPublicKeyRepository = sshAgentPublicKeyRepository,
            pendingUsageHistoryQueue = pendingUsageHistoryQueue,
            sessionId = sessionId,
            onApprovalRequest = onApprovalRequest,
        ),
        maxConcurrentConnections = maxConcurrentConnections,
    )

    private val host = AgentIpcServerHost(
        logRepository = logRepository,
        tag = TAG,
        maxConnections = maxConcurrentConnections,
    )

    /**
     * Creates the private endpoint at [location], which exists once this
     * returns, and returns the value of the helper's `--ipc-socket`.
     */
    fun bind(location: AgentIpcLocation): String = host.bind(location)

    /**
     * Serves the helper's connections until [close]. Accepts nothing before
     * [expectedPeer] returns, and only connections of that process.
     */
    suspend fun serve(expectedPeer: suspend () -> AgentIpcExpectedPeer) {
        host.serve(expectedPeer) { connection ->
            SshAgentPacketSession(rpcHandler, authenticatedByTransport = false)
                .serveFramed(connection.asAgentByteStream())
        }
    }

    /** Closes the endpoint and every connection, and erases the token. */
    fun close() {
        host.close()
        authenticator.close()
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
            context = AgentRpcRequestContext(
                authenticated = authenticated,
                allowAuthenticate = true,
            ),
        )

    /**
     * Handles the authentication handshake.
     */
    internal fun handleAuthenticate(
        requestId: Long,
        req: AgentIpcMessages.AuthenticateRequest,
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
