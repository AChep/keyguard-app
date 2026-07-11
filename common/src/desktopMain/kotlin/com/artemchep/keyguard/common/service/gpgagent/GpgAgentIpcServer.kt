package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
import com.artemchep.keyguard.common.service.agent.AgentIpcPeerVerificationPolicy
import com.artemchep.keyguard.common.service.agent.AgentIpcServer
import com.artemchep.keyguard.common.service.agent.TestOnlyUnverifiedAgentIpcApi
import com.artemchep.keyguard.common.service.agent.TestOnlyUnverifiedAgentIpcPeer
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestProcessorImpl
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindowNoOp
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicyNoOp
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.crypto.GpgAgentCryptoJvm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import java.nio.file.Path
import java.security.MessageDigest

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

        const val APPROVAL_TIMEOUT_MS = GpgAgentRequestProcessorImpl.APPROVAL_TIMEOUT_MS
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

    private val rpcHandler = GpgAgentRpcHandler(
        requestProcessor = requestProcessor,
        authenticate = { req ->
            val tokenMatches = MessageDigest.isEqual(authToken, req.token)
            val revisionMatches = req.protocolRevision == GpgAgentMessages.PROTOCOL_REVISION
            val success = tokenMatches && revisionMatches
            if (!revisionMatches) {
                logRepository.post(
                    TAG,
                    "GPG agent authentication failed: protocol revision mismatch " +
                        "(expected=${GpgAgentMessages.PROTOCOL_REVISION}, " +
                        "actual=${req.protocolRevision})",
                    LogLevel.ERROR,
                )
            } else if (!tokenMatches) {
                logRepository.post(TAG, "GPG agent authentication failed: token mismatch", LogLevel.ERROR)
            } else {
                logRepository.post(TAG, "GPG agent authentication successful", LogLevel.INFO)
            }
            success
        },
    )

    constructor(
        logRepository: LogRepository,
        getVaultSession: GetVaultSession,
        getGpgAgentApprovalWindow: GetGpgAgentApprovalWindow = GetGpgAgentApprovalWindowNoOp,
        getGpgAgentApprovalCachePolicy: GetGpgAgentApprovalCachePolicy =
            GetGpgAgentApprovalCachePolicyNoOp,
        getGpgAgentFilter: GetGpgAgentFilter,
        authToken: ByteArray,
        scope: CoroutineScope,
        expectedPeerProcess: Deferred<Process>,
        sessionId: String = "",
        maxConcurrentConnections: Int = 8,
        onApprovalRequest: suspend (
            operation: GpgAgentOperation,
            caller: GpgAgentMessages.CallerIdentity?,
            keyName: String,
            keyFingerprint: String,
            keygrip: String,
        ) -> Boolean = { _, _, _, _, _ -> true },
        gpgAgentPublicKeyRepository: GpgAgentPublicKeyRepository = GpgAgentPublicKeyRepositoryEmpty,
    ) : this(
        logRepository = logRepository,
        authToken = authToken,
        scope = scope,
        requestProcessor = GpgAgentRequestProcessorImpl(
            logRepository = logRepository,
            crypto = GpgAgentCryptoJvm(),
            getVaultSession = getVaultSession,
            getGpgAgentApprovalWindow = getGpgAgentApprovalWindow,
            getGpgAgentApprovalCachePolicy = getGpgAgentApprovalCachePolicy,
            getGpgAgentFilter = getGpgAgentFilter,
            scope = scope,
            gpgAgentPublicKeyRepository = gpgAgentPublicKeyRepository,
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
        getGpgAgentApprovalWindow: GetGpgAgentApprovalWindow = GetGpgAgentApprovalWindowNoOp,
        getGpgAgentApprovalCachePolicy: GetGpgAgentApprovalCachePolicy =
            GetGpgAgentApprovalCachePolicyNoOp,
        getGpgAgentFilter: GetGpgAgentFilter,
        authToken: ByteArray,
        scope: CoroutineScope,
        testOnlyUnverifiedPeer: TestOnlyUnverifiedAgentIpcPeer,
        sessionId: String = "",
        maxConcurrentConnections: Int = 8,
        onApprovalRequest: suspend (
            operation: GpgAgentOperation,
            caller: GpgAgentMessages.CallerIdentity?,
            keyName: String,
            keyFingerprint: String,
            keygrip: String,
        ) -> Boolean = { _, _, _, _, _ -> true },
        gpgAgentPublicKeyRepository: GpgAgentPublicKeyRepository = GpgAgentPublicKeyRepositoryEmpty,
    ) : this(
        logRepository = logRepository,
        authToken = authToken,
        scope = scope,
        requestProcessor = GpgAgentRequestProcessorImpl(
            logRepository = logRepository,
            crypto = GpgAgentCryptoJvm(),
            getVaultSession = getVaultSession,
            getGpgAgentApprovalWindow = getGpgAgentApprovalWindow,
            getGpgAgentApprovalCachePolicy = getGpgAgentApprovalCachePolicy,
            getGpgAgentFilter = getGpgAgentFilter,
            scope = scope,
            gpgAgentPublicKeyRepository = gpgAgentPublicKeyRepository,
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
            runGpgAgentPacketSession(
                channel = channel,
                rpcHandler = rpcHandler,
                initialContext = GpgAgentRpcRequestContext(
                    authenticated = false,
                    allowAuthenticate = true,
                ),
            )
        },
    )

    internal fun stop() = server.stop()

    /** Processes the authentication handshake without opening a transport. */
    internal fun handleAuthenticate(
        requestId: Long,
        request: GpgAgentMessages.AuthenticateRequest,
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
