package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.AgentManager
import com.artemchep.keyguard.common.service.agent.macosDevAgentSocketPath
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.util.flow.EventFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the lifecycle of the keyguard-ssh-agent Rust binary.
 *
 * The generic process/socket lifecycle lives in [AgentManager]; this
 * subclass wires up the SSH-specific IPC server and routes sign-request
 * approval prompts to the UI via [approvalRequests], plus vault-unlock
 * prompts via [getListRequests].
 */
class SshAgentManager(
    logRepository: LogRepository,
    cryptoGenerator: CryptoGenerator,
    private val getVaultSession: GetVaultSession,
    private val getSshAgentApprovalWindow: GetSshAgentApprovalWindow,
    private val getSshAgentApprovalCachePolicy: GetSshAgentApprovalCachePolicy,
    private val getSshAgentFilter: GetSshAgentFilter,
    private val sshAgentPublicKeyRepository: SshAgentPublicKeyRepository,
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
     * Flow of pending approval requests from the SSH agent.
     *
     * The Compose UI should collect this flow and show an approval
     * dialog for each emitted [SshAgentApprovalRequest]. Completing
     * the request's [CompletableDeferred] with `true` approves the
     * signing; `false` (or letting it time out) denies it.
     */
    val approvalRequests = EventFlow<SshAgentApprovalRequest>()

    /**
     * Flow of pending list-key requests from the SSH agent.
     *
     * Emitted when an SSH agent list-key operation arrives while the
     * vault is locked. The Compose UI should collect this flow and show
     * an unlock prompt. Completing the request's
     * [CompletableDeferred] with `true` indicates the vault was
     * unlocked; `false` (or timeout) indicates failure.
     *
     * Multiple concurrent list-key requests that need the vault unlocked
     * are coalesced into a single request — only one window
     * is shown at a time.
     */
    val getListRequests = EventFlow<SshAgentGetListRequest>()

    val requestsFlow = merge(
        getListRequests,
        approvalRequests,
    )

    /**
     * Mutex protecting the [pendingGetListRequest] for coalescing.
     */
    private val unlockMutex = Mutex()

    /**
     * The currently active list-key request, if an unlock prompt is
     * already being shown. Multiple list-key requests that arrive
     * while the vault is locked share this single request.
     */
    private var pendingGetListRequest: SshAgentGetListRequest? = null

    override fun createIpcServer(
        authToken: ByteArray,
        sessionId: String,
        scope: CoroutineScope,
        expectedPeerProcess: Deferred<Process>,
    ): IpcServerRunner {
        val ipcServer = SshAgentIpcServer(
            logRepository = logRepository,
            getVaultSession = getVaultSession,
            getSshAgentApprovalWindow = getSshAgentApprovalWindow,
            getSshAgentApprovalCachePolicy = getSshAgentApprovalCachePolicy,
            getSshAgentFilter = getSshAgentFilter,
            sshAgentPublicKeyRepository = sshAgentPublicKeyRepository,
            authToken = authToken,
            scope = scope,
            expectedPeerProcess = expectedPeerProcess,
            sessionId = sessionId,
            onApprovalRequest = { caller, keyName, keyFingerprint ->
                val deferred = CompletableDeferred<Boolean>()
                val request = SshAgentApprovalRequest(
                    keyName = keyName,
                    keyFingerprint = keyFingerprint,
                    caller = caller,
                    notificationTag = null,
                    expiresAt = Clock.System.now() + SshAgentIpcServer.APPROVAL_TIMEOUT_MS.milliseconds,
                    deferred = deferred,
                )
                approvalRequests.emit(request)
                awaitWithExpiry(request, reason = "desktop_approval_timeout")
            },
            onGetListRequest = {
                requestGetList(it)
            },
        )
        return IpcServerRunner { endpoint, onReady ->
            ipcServer.start(endpoint, onReady = onReady)
        }
    }

    /**
     * Requests the user to unlock the vault for a list-key operation.
     *
     * This method coalesces multiple concurrent list-key requests:
     * if an unlock prompt is already being shown, callers share
     * the existing deferred instead of spawning a new window.
     *
     * @return `true` if the vault was successfully unlocked,
     *   `false` if the user dismissed or the timeout expired.
     */
    private suspend fun requestGetList(
        caller: SshAgentMessages.CallerIdentity?,
    ): Boolean {
        val request = unlockMutex.withLock {
            // If a list-key unlock is already in progress,
            // share the deferred.
            pendingGetListRequest?.let { existing ->
                return@withLock existing
            }

            val deferred = CompletableDeferred<Boolean>()
            val request = SshAgentGetListRequest(
                caller = caller,
                notificationTag = null,
                expiresAt = Clock.System.now() + SshAgentIpcServer.APPROVAL_TIMEOUT_MS.milliseconds,
                deferred = deferred,
            )
            pendingGetListRequest = request
            getListRequests.emit(request)
            request
        }
        return try {
            awaitWithExpiry(request, reason = "desktop_get_list_timeout")
        } finally {
            unlockMutex.withLock {
                if (pendingGetListRequest === request) {
                    pendingGetListRequest = null
                }
            }
        }
    }
}
