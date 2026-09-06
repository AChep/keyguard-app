package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
import com.artemchep.keyguard.common.service.agent.AgentManager
import com.artemchep.keyguard.common.service.agent.macosDevAgentSocketPath
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.copy.DataDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the lifecycle of the keyguard-gpg-agent Rust binary.
 *
 * The generic process/socket lifecycle lives in [AgentManager]; this
 * subclass wires up the GPG-specific IPC server and routes approval
 * prompts to the UI via [approvalRequests]. The platform-specific
 * GnuPG home and socket discovery lives in [GpgAgentSocketResolver].
 */
class GpgAgentManager(
    logRepository: LogRepository,
    cryptoGenerator: CryptoGenerator,
    dataDirectory: DataDirectory,
    private val getVaultSession: GetVaultSession,
    private val getGpgAgentApprovalWindow: GetGpgAgentApprovalWindow,
    private val getGpgAgentApprovalCachePolicy: GetGpgAgentApprovalCachePolicy,
    private val getGpgAgentFilter: GetGpgAgentFilter,
    private val gpgPublicKeyRepository: GpgPublicKeyRepository,
    private val pendingUsageHistoryQueue: PendingUsageHistoryQueue? = null,
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

    private val socketResolver = GpgAgentSocketResolver(
        logRepository = logRepository,
        dataDirectory = dataDirectory,
    )

    suspend fun resolveGpgAgentSocketPathOrNull(): Path? = socketResolver.resolveOrNull()

    override fun createIpcServer(
        authToken: ByteArray,
        sessionId: String,
        scope: CoroutineScope,
        expectedPeerProcess: Deferred<Process>,
    ): IpcServerRunner {
        val ipcServer = GpgAgentIpcServer(
            logRepository = logRepository,
            getVaultSession = getVaultSession,
            getGpgAgentApprovalWindow = getGpgAgentApprovalWindow,
            getGpgAgentApprovalCachePolicy = getGpgAgentApprovalCachePolicy,
            getGpgAgentFilter = getGpgAgentFilter,
            gpgPublicKeyRepository = gpgPublicKeyRepository,
            pendingUsageHistoryQueue = pendingUsageHistoryQueue,
            authToken = authToken,
            scope = scope,
            expectedPeerProcess = expectedPeerProcess,
            sessionId = sessionId,
            onApprovalRequest = { prompt ->
                val deferred = CompletableDeferred<Boolean>()
                val request = GpgAgentApprovalRequest(
                    operation = prompt.operation,
                    keyName = prompt.keyName,
                    keyFingerprint = prompt.keyFingerprint,
                    keygrip = prompt.keygrip,
                    accountId = prompt.accountId,
                    cipherId = prompt.cipherId,
                    caller = prompt.caller,
                    notificationTag = null,
                    expiresAt = Clock.System.now() + GpgAgentIpcServer.APPROVAL_TIMEOUT_MS.milliseconds,
                    deferred = deferred,
                )
                approvalRequests.emit(request)
                awaitWithExpiry(request, reason = "desktop_gpg_approval_timeout")
            },
        )
        return object : IpcServerRunner {
            override suspend fun start(
                endpoint: AgentIpcEndpoint,
                onReady: CompletableDeferred<Unit>?,
            ) {
                ipcServer.start(endpoint, onReady = onReady)
            }

            override fun stop() {
                ipcServer.stop()
            }
        }
    }
}
