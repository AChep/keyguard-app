package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentManager
import com.artemchep.keyguard.common.service.agent.macosDevAgentSocketPath
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.util.flow.EventFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the lifecycle of the keyguard-gpg-agent Rust binary.
 *
 * The generic process/socket lifecycle lives in [AgentManager]; this
 * subclass wires up the GPG-specific IPC server and routes approval
 * prompts to the UI via [approvalRequests].
 */
class GpgAgentManager(
    logRepository: LogRepository,
    cryptoGenerator: CryptoGenerator,
    private val getVaultSession: GetVaultSession,
    private val getGpgAgentApprovalWindow: GetGpgAgentApprovalWindow,
    private val getGpgAgentFilter: GetGpgAgentFilter,
    private val gpgAgentPublicKeyRepository: GpgAgentPublicKeyRepository,
) : AgentManager(
    logRepository = logRepository,
    cryptoGenerator = cryptoGenerator,
    config = Config(
        tag = "GpgAgentManager",
        displayName = "GPG agent",
        binaryBaseName = "keyguard-gpg-agent",
        ipcSocketPrefix = "keyguard-gpg-ipc",
        agentSocketArg = "--gpg-socket",
        agentSocketLogLabel = "GPG socket",
        defaultAgentSocketPath = macosDevAgentSocketPath("gnupg/S.gpg-agent"),
    ),
) {
    val approvalRequests = EventFlow<GpgAgentApprovalRequest>()

    val requestsFlow = approvalRequests

    override fun createIpcServer(
        authToken: ByteArray,
        sessionId: String,
        scope: CoroutineScope,
    ): IpcServerRunner {
        val ipcServer = GpgAgentIpcServer(
            logRepository = logRepository,
            getVaultSession = getVaultSession,
            getGpgAgentApprovalWindow = getGpgAgentApprovalWindow,
            getGpgAgentFilter = getGpgAgentFilter,
            gpgAgentPublicKeyRepository = gpgAgentPublicKeyRepository,
            authToken = authToken,
            scope = scope,
            sessionId = sessionId,
            onApprovalRequest = { operation, caller, keyName, keyFingerprint, keygrip ->
                val deferred = CompletableDeferred<Boolean>()
                val request = GpgAgentApprovalRequest(
                    operation = operation,
                    keyName = keyName,
                    keyFingerprint = keyFingerprint,
                    keygrip = keygrip,
                    caller = caller,
                    notificationTag = null,
                    expiresAt = Clock.System.now() + GpgAgentIpcServer.APPROVAL_TIMEOUT_MS.milliseconds,
                    deferred = deferred,
                )
                approvalRequests.emit(request)
                awaitWithExpiry(request, reason = "desktop_gpg_approval_timeout")
            },
        )
        return IpcServerRunner { socketPath, onReady ->
            ipcServer.start(socketPath, onReady = onReady)
        }
    }
}
