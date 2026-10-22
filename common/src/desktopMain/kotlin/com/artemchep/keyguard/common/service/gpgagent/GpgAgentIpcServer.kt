package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentIpcAuthenticator
import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.agent.AgentIpcPeerVerificationPolicy
import com.artemchep.keyguard.common.service.agent.AgentIpcServer
import com.artemchep.keyguard.common.service.agent.TestOnlyUnverifiedAgentIpcApi
import com.artemchep.keyguard.common.service.agent.TestOnlyUnverifiedAgentIpcPeer
import com.artemchep.keyguard.common.service.agent.checkAndLog
import com.artemchep.keyguard.common.service.agent.serve
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
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred

class GpgAgentIpcServer private constructor(
    private val logRepository: LogRepository,
    private val authToken: ByteArray,
    private val scope: CoroutineScope,
    private val requestProcessor: GpgAgentRequestProcessor,
    private val maxConcurrentConnections: Int = 8,
    private val peerVerificationPolicy: AgentIpcPeerVerificationPolicy,
) {
    companion object {
        private const val TAG = "GpgAgentIpcServer"
    }

    constructor(
        logRepository: LogRepository,
        authToken: ByteArray,
        scope: CoroutineScope,
        requestProcessor: GpgAgentRequestProcessor,
        maxConcurrentConnections: Int = 8,
        expectedPeerProcess: Deferred<Process>,
    ) : this(
        logRepository = logRepository,
        authToken = authToken,
        scope = scope,
        requestProcessor = requestProcessor,
        maxConcurrentConnections = maxConcurrentConnections,
        peerVerificationPolicy = AgentIpcPeerVerificationPolicy.ExactProcess(expectedPeerProcess),
    )

    @TestOnlyUnverifiedAgentIpcApi
    internal constructor(
        logRepository: LogRepository,
        authToken: ByteArray,
        scope: CoroutineScope,
        requestProcessor: GpgAgentRequestProcessor,
        maxConcurrentConnections: Int = 8,
        testOnlyUnverifiedPeer: TestOnlyUnverifiedAgentIpcPeer,
    ) : this(
        logRepository = logRepository,
        authToken = authToken,
        scope = scope,
        requestProcessor = requestProcessor,
        maxConcurrentConnections = maxConcurrentConnections,
        peerVerificationPolicy = AgentIpcPeerVerificationPolicy.TestOnlyUnverified,
    )

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
        expectedPeerProcess: Deferred<Process>,
        sessionId: String = "",
        maxConcurrentConnections: Int = 8,
        onApprovalRequest: suspend (GpgAgentApprovalPrompt) -> Boolean = { true },
        pendingUsageHistoryQueue: PendingUsageHistoryQueue? = null,
        gpgPublicKeyRepository: GpgPublicKeyRepository = GpgPublicKeyRepositoryEmpty,
    ) : this(
        logRepository = logRepository,
        authToken = authToken,
        scope = scope,
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
        expectedPeerProcess = expectedPeerProcess,
    )

    @TestOnlyUnverifiedAgentIpcApi
    internal constructor(
        logRepository: LogRepository,
        getVaultSession: GetVaultSession,
        sessionAccess: GpgAgentSessionAccess,
        getGpgAgentApprovalWindow: GetGpgAgentApprovalWindow = GetGpgAgentApprovalWindowNoOp,
        getGpgAgentApprovalCachePolicy: GetGpgAgentApprovalCachePolicy =
            GetGpgAgentApprovalCachePolicyNoOp,
        getGpgAgentFilter: GetGpgAgentFilter,
        authToken: ByteArray,
        scope: CoroutineScope,
        testOnlyUnverifiedPeer: TestOnlyUnverifiedAgentIpcPeer,
        sessionId: String = "",
        maxConcurrentConnections: Int = 8,
        onApprovalRequest: suspend (GpgAgentApprovalPrompt) -> Boolean = { true },
        pendingUsageHistoryQueue: PendingUsageHistoryQueue? = null,
        gpgPublicKeyRepository: GpgPublicKeyRepository = GpgPublicKeyRepositoryEmpty,
    ) : this(
        logRepository = logRepository,
        authToken = authToken,
        scope = scope,
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
        testOnlyUnverifiedPeer = testOnlyUnverifiedPeer,
    )

    private val server = AgentIpcServer(
        logRepository = logRepository,
        scope = scope,
        tag = TAG,
        maxConcurrentConnections = maxConcurrentConnections,
        peerVerificationPolicy = peerVerificationPolicy,
        session = { channel ->
            GpgAgentPacketSession(rpcHandler, authenticatedByTransport = false)
                .serve(channel)
        },
    )

    internal fun stop() {
        server.stop()
        authenticator.close()
    }

    /** Processes the authentication handshake without opening a transport. */
    internal fun handleAuthenticate(
        requestId: Long,
        request: AgentIpcMessages.AuthenticateRequest,
    ): GpgAgentMessages.IpcResponse = rpcHandler.handleAuthenticate(requestId, request)

    suspend fun start(
        socketPath: Path,
        onReady: CompletableDeferred<Unit>? = null,
    ) = server.start(socketPath, onReady)

    suspend fun start(
        endpoint: AgentIpcEndpoint,
        onReady: CompletableDeferred<Unit>? = null,
    ) = server.start(endpoint, onReady)
}
