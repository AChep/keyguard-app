package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.AgentApprovalBroker
import com.artemchep.keyguard.common.service.agent.AgentManager
import com.artemchep.keyguard.common.service.agent.denyWhenSessionEnds
import com.artemchep.keyguard.common.service.agent.macosDevAgentSocketPath
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.session.SshAgentSessionAccess
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.util.agentipc.AgentIpcExpectedPeer
import com.artemchep.keyguard.util.agentipc.AgentIpcLocation
import kotlinx.coroutines.*

/**
 * Manages the lifecycle of the keyguard-ssh-agent Rust binary.
 *
 * The generic process/socket lifecycle lives in [AgentManager]; this
 * subclass wires up the SSH-specific IPC server and routes sign-request
 * approval prompts to the UI via [approvals].
 */
class SshAgentManager(
    logRepository: LogRepository,
    cryptoGenerator: CryptoGenerator,
    private val getVaultSession: GetVaultSession,
    private val sessionAccess: SshAgentSessionAccess,
    private val getSshAgentApprovalWindow: GetSshAgentApprovalWindow,
    private val getSshAgentApprovalCachePolicy: GetSshAgentApprovalCachePolicy,
    private val getSshAgentFilter: GetSshAgentFilter,
    private val sshAgentPublicKeyRepository: SshAgentPublicKeyRepository,
    private val pendingUsageHistoryQueue: PendingUsageHistoryQueue? = null,
) : AgentManager(
    logRepository = logRepository,
    cryptoGenerator = cryptoGenerator,
    config = Config(
        tag = "SshAgentManager",
        displayName = "SSH agent",
        binaryBaseName = "keyguard-ssh-agent",
        ipcSocketPrefix = "keyguard-ipc",
        agentSocketArg = "--ssh-socket",
        agentSocketLogLabel = "SSH socket",
        defaultAgentSocketPath = macosDevAgentSocketPath("ssh-agent.sock"),
    ),
) {
    /**
     * Pending approval requests from the SSH agent. The UI shows an
     * approval dialog for each and responds to it; a request that is not
     * answered before it expires is denied.
     */
    val approvals = AgentApprovalBroker<SshAgentApprovalRequest>(idOf = { it.id })

    override fun createIpcServer(
        authToken: ByteArray,
        sessionId: String,
        scope: CoroutineScope,
    ): IpcServerRunner {
        scope.launch { approvals.denyWhenSessionEnds(getVaultSession()) }
        val ipcServer = SshAgentIpcServer(
            logRepository = logRepository,
            getVaultSession = getVaultSession,
            sessionAccess = sessionAccess,
            getSshAgentApprovalWindow = getSshAgentApprovalWindow,
            getSshAgentApprovalCachePolicy = getSshAgentApprovalCachePolicy,
            getSshAgentFilter = getSshAgentFilter,
            sshAgentPublicKeyRepository = sshAgentPublicKeyRepository,
            pendingUsageHistoryQueue = pendingUsageHistoryQueue,
            authToken = authToken,
            scope = scope,
            sessionId = sessionId,
            onApprovalRequest = { prompt ->
                val result = approvals.request { expiresAt ->
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
