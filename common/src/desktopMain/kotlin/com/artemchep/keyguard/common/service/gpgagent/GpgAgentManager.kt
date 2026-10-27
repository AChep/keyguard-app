package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentManager
import com.artemchep.keyguard.common.service.agent.denyWhenSessionEnds
import com.artemchep.keyguard.common.service.agent.macosDevAgentSocketPath
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.session.GpgAgentSessionAccess
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.service.agent.AgentApprovalBroker
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.util.agentipc.AgentIpcExpectedPeer
import com.artemchep.keyguard.util.agentipc.AgentIpcLocation
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Manages the lifecycle of the keyguard-gpg-agent Rust binary.
 *
 * The generic process/socket lifecycle lives in [AgentManager]; this
 * subclass wires up the GPG-specific IPC server and routes approval
 * prompts to the UI via [approvals]. The platform-specific
 * GnuPG home and socket discovery lives in [GpgAgentSocketResolver].
 */
class GpgAgentManager(
    logRepository: LogRepository,
    cryptoGenerator: CryptoGenerator,
    dataDirectory: DataDirectory,
    private val getVaultSession: GetVaultSession,
    private val sessionAccess: GpgAgentSessionAccess,
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
    /**
     * Pending approval requests from the GPG agent. The UI shows an
     * approval dialog for each and responds to it; a request that is not
     * answered before it expires is denied.
     */
    val approvals = AgentApprovalBroker<GpgAgentApprovalRequest>(idOf = { it.id })

    private val socketResolver = GpgAgentSocketResolver(
        logRepository = logRepository,
        dataDirectory = dataDirectory,
    )

    suspend fun resolveGpgAgentSocketPathOrNull(): Path? = socketResolver.resolveOrNull()

    suspend fun resolveHelperArguments(): List<String> = socketResolver.helperArguments()

    override fun createIpcServer(
        authToken: ByteArray,
        sessionId: String,
        scope: CoroutineScope,
    ): IpcServerRunner {
        scope.launch { approvals.denyWhenSessionEnds(getVaultSession()) }
        val ipcServer = GpgAgentIpcServer(
            logRepository = logRepository,
            getVaultSession = getVaultSession,
            sessionAccess = sessionAccess,
            getGpgAgentApprovalWindow = getGpgAgentApprovalWindow,
            getGpgAgentApprovalCachePolicy = getGpgAgentApprovalCachePolicy,
            getGpgAgentFilter = getGpgAgentFilter,
            gpgPublicKeyRepository = gpgPublicKeyRepository,
            pendingUsageHistoryQueue = pendingUsageHistoryQueue,
            authToken = authToken,
            scope = scope,
            sessionId = sessionId,
            onApprovalRequest = { prompt ->
                val result = approvals.request(deadline = prompt.expiresAt) { expiresAt ->
                    prompt.toApprovalRequest(
                        id = cryptoGenerator.uuid(),
                        expiresAt = expiresAt,
                    )
                }
                result == AgentApprovalBroker.Result.Approved
            },
        )
        return object : IpcServerRunner {
            override fun bind(location: AgentIpcLocation) = ipcServer.bind(location)

            override suspend fun serve(expectedPeer: suspend () -> AgentIpcExpectedPeer) {
                ipcServer.serve(expectedPeer)
            }

            override fun close() {
                ipcServer.close()
            }
        }
    }
}
