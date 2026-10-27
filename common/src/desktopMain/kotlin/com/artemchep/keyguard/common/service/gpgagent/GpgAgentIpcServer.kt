package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentIpcAuthenticator
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.agent.AgentIpcServerHost
import com.artemchep.keyguard.common.service.agent.asAgentByteStream
import com.artemchep.keyguard.common.service.agent.checkAndLog
import com.artemchep.keyguard.common.service.agent.serveFramed
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestProcessorImpl
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.session.GpgAgentSessionAccess
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicyNoOp
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindowNoOp
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.crypto.NativeGpgAgentCrypto
import com.artemchep.keyguard.util.agentipc.AgentIpcExpectedPeer
import com.artemchep.keyguard.util.agentipc.AgentIpcLocation
import kotlinx.coroutines.CoroutineScope

class GpgAgentIpcServer(
    private val logRepository: LogRepository,
    authToken: ByteArray,
    requestProcessor: GpgAgentRequestProcessor,
    maxConcurrentConnections: Int = 8,
) {
    companion object {
        private const val TAG = "GpgAgentIpcServer"
    }

    private val authenticator = AgentIpcAuthenticator(authToken)

    private val rpcHandler = GpgAgentRpcHandler(
        requestProcessor = requestProcessor,
        authenticate = { req ->
            authenticator.checkAndLog(req, logRepository, TAG, agentName = "GPG agent")
        },
    )

    constructor(
        logRepository: LogRepository,
        getVaultSession: GetVaultSession,
        sessionAccess: GpgAgentSessionAccess,
        getGpgAgentApprovalWindow: GetGpgAgentApprovalWindow = GetGpgAgentApprovalWindowNoOp,
        getGpgAgentApprovalCachePolicy: GetGpgAgentApprovalCachePolicy =
            GetGpgAgentApprovalCachePolicyNoOp,
        getGpgAgentFilter: GetGpgAgentFilter,
        authToken: ByteArray,
        scope: CoroutineScope,
        sessionId: String = "",
        maxConcurrentConnections: Int = 8,
        onApprovalRequest: suspend (GpgAgentApprovalPrompt) -> Boolean = { true },
        pendingUsageHistoryQueue: PendingUsageHistoryQueue? = null,
        gpgPublicKeyRepository: GpgPublicKeyRepository = GpgPublicKeyRepositoryEmpty,
    ) : this(
        logRepository = logRepository,
        authToken = authToken,
        requestProcessor = GpgAgentRequestProcessorImpl(
            logRepository = logRepository,
            crypto = NativeGpgAgentCrypto,
            getVaultSession = getVaultSession,
            sessionAccess = sessionAccess,
            getGpgAgentApprovalWindow = getGpgAgentApprovalWindow,
            getGpgAgentApprovalCachePolicy = getGpgAgentApprovalCachePolicy,
            getGpgAgentFilter = getGpgAgentFilter,
            scope = scope,
            gpgPublicKeyRepository = gpgPublicKeyRepository,
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
            GpgAgentPacketSession(rpcHandler, authenticatedByTransport = false)
                .serveFramed(connection.asAgentByteStream())
        }
    }

    /** Closes the endpoint and every connection, and erases the token. */
    fun close() {
        host.close()
        authenticator.close()
    }

    /** Processes the authentication handshake without opening a transport. */
    internal fun handleAuthenticate(
        requestId: Long,
        request: AgentIpcMessages.AuthenticateRequest,
    ): GpgAgentMessages.IpcResponse = rpcHandler.handleAuthenticate(requestId, request)
}
