package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
import com.artemchep.keyguard.common.service.agent.AgentIpcServer
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestProcessorImpl
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindowNoOp
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.crypto.GpgAgentCryptoJvm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path
import java.security.MessageDigest

class GpgAgentIpcServer(
    private val logRepository: LogRepository,
    private val authToken: ByteArray,
    private val scope: CoroutineScope,
    private val requestProcessor: GpgAgentRequestProcessor,
    private val maxConcurrentConnections: Int = 8,
) {
    companion object {
        private const val TAG = "GpgAgentIpcServer"

        const val APPROVAL_TIMEOUT_MS = GpgAgentRequestProcessorImpl.APPROVAL_TIMEOUT_MS
    }

    private val rpcHandler = GpgAgentRpcHandler(
        requestProcessor = requestProcessor,
        authenticate = { req ->
            val success = MessageDigest.isEqual(authToken, req.token)
            if (!success) {
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
        getGpgAgentFilter: GetGpgAgentFilter,
        authToken: ByteArray,
        scope: CoroutineScope,
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
            getGpgAgentFilter = getGpgAgentFilter,
            scope = scope,
            gpgAgentPublicKeyRepository = gpgAgentPublicKeyRepository,
            sessionId = sessionId,
            onApprovalRequest = onApprovalRequest,
        ),
        maxConcurrentConnections = maxConcurrentConnections,
    )

    private val server = AgentIpcServer(
        logRepository = logRepository,
        scope = scope,
        tag = TAG,
        maxConcurrentConnections = maxConcurrentConnections,
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

    suspend fun start(
        socketPath: Path,
        onReady: CompletableDeferred<Unit>? = null,
    ) = server.start(socketPath, onReady)

    suspend fun start(
        endpoint: AgentIpcEndpoint,
        onReady: CompletableDeferred<Unit>? = null,
    ) = server.start(endpoint, onReady)
}
