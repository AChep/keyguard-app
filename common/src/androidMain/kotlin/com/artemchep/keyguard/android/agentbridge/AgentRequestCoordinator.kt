package com.artemchep.keyguard.android.agentbridge

import android.content.Context
import android.content.Intent
import android.util.Log
import com.artemchep.keyguard.common.service.agent.AgentApprovalBroker
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentApprovalRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalRequest
import com.artemchep.keyguard.feature.agent.AgentRequestUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Instant

internal object AgentRequestCoordinator {
    private const val TAG = "AgentRequestCoordinator"
    private const val LAUNCH_CONFIRMATION_TIMEOUT_MS = 750L

    private val launchConfirmationTracker = AgentRequestLaunchConfirmationTracker()

    /** The SSH signing requests that wait for the user, shared by every SSH bridge. */
    val sshApprovals = AgentApprovalBroker<SshAgentApprovalRequest>(idOf = { it.id })

    /** The GPG signing and decryption requests that wait for the user, shared by every GPG bridge. */
    val gpgApprovals = AgentApprovalBroker<GpgAgentApprovalRequest>(idOf = { it.id })

    private val brokers = listOf(sshApprovals, gpgApprovals)

    /** The requests of both agents, so one prompt shows them one at a time. */
    val pending: StateFlow<List<AgentBridgePrompt>> = combine(
        sshApprovals.pending,
        gpgApprovals.pending,
    ) { sshPrompts, gpgPrompts ->
        val prompts = sshPrompts.map(AgentBridgePrompt::Ssh) + gpgPrompts.map(AgentBridgePrompt::Gpg)
        // A prompt expires a fixed time after it is shown, so its deadline orders it.
        prompts.sortedBy { it.expiresAt }
    }.stateIn(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    /** Answers the request [id] of whichever agent owns it. */
    fun respond(id: String, approved: Boolean): Boolean =
        brokers.any { it.respond(id, approved) }

    /** See [dismissPrompt]. */
    fun dismissPrompt(shown: AgentRequestUiState<*>?): Boolean = brokers.dismissPrompt(shown)

    /** The open request prompts; a request without one falls back to its notification. */
    val prompts = AgentRequestPromptTracker()

    /** Denies the request the dismissed notification [notificationTag] represents. */
    fun dismissRequest(notificationTag: String) {
        sshApprovals.pending.value
            .firstOrNull { it.notificationTag == notificationTag }
            ?.let { sshApprovals.respond(it.id, false) }
        gpgApprovals.pending.value
            .firstOrNull { it.notificationTag == notificationTag }
            ?.let { gpgApprovals.respond(it.id, false) }
    }

    fun confirmLaunch(intent: Intent?) {
        launchConfirmationTracker.acknowledge(
            launchId = AgentRequestActivity.getLaunchId(intent),
        )
    }

    suspend fun show(context: Context): Boolean {
        val launchId = launchConfirmationTracker.nextLaunchId()
        val intent = AgentRequestActivity.getIntent(
            context = context,
            launchId = launchId,
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        val started = withContext(Dispatchers.Main.immediate) {
            runCatching {
                Log.i(TAG, "Starting the agent request activity launchId=$launchId")
                context.startActivity(intent)
            }.onFailure { e ->
                Log.w(TAG, "Unable to show agent request activity launchId=$launchId", e)
            }.isSuccess
        }
        if (!started) {
            return false
        }

        val confirmed = launchConfirmationTracker.awaitLaunch(
            launchId = launchId,
            timeoutMillis = LAUNCH_CONFIRMATION_TIMEOUT_MS,
        )
        if (!confirmed) {
            val msg = "Agent request activity launch attempted but not confirmed " +
                    "launchId=$launchId timeoutMs=$LAUNCH_CONFIRMATION_TIMEOUT_MS"
            Log.w(TAG, msg)
        }
        return confirmed
    }
}

/** A pending request of either Termux bridge, as the request prompt shows it. */
internal sealed interface AgentBridgePrompt {
    val id: String
    val expiresAt: Instant
    val bridge: AgentBridge

    data class Ssh(val request: SshAgentApprovalRequest) : AgentBridgePrompt {
        override val id: String get() = request.id
        override val expiresAt: Instant get() = request.expiresAt
        override val bridge: AgentBridge get() = AgentBridge.SSH
    }

    data class Gpg(val request: GpgAgentApprovalRequest) : AgentBridgePrompt {
        override val id: String get() = request.id
        override val expiresAt: Instant get() = request.expiresAt
        override val bridge: AgentBridge get() = AgentBridge.GPG
    }
}

/**
 * The agent the prompt asks to unlock for: the request on screen, else the
 * next one waiting, else the agent a recovery launch starts. Null when none
 * is known, so the prompt names no agent rather than a wrong one.
 */
internal fun agentRequestUnlockBridge(
    shown: AgentBridgePrompt?,
    pending: List<AgentBridgePrompt>,
    recovery: AgentBridge?,
): AgentBridge? = shown?.bridge
    ?: pending.firstOrNull()?.bridge
    ?: recovery

/**
 * Denies the [shown] request the user dismissed, if the prompt shows one.
 * Returns whether the prompt may close: the requests that wait behind the
 * dismissed one still need an answer.
 */
internal fun List<AgentApprovalBroker<*>>.dismissPrompt(
    shown: AgentRequestUiState<*>?,
): Boolean {
    shown?.onDecision?.invoke(false, "activity_dismissed")
    return all { it.pending.value.isEmpty() }
}

/** The open request prompts, by a token that a prompt keeps across configuration changes. */
internal class AgentRequestPromptTracker {
    private val tokens = MutableStateFlow<Set<String>>(emptySet())

    fun open(token: String) {
        tokens.update { it + token }
    }

    fun close(token: String) {
        tokens.update { it - token }
    }

    /** Suspends until no prompt is open. */
    suspend fun awaitClosed() {
        tokens.first { it.isEmpty() }
    }
}

internal class AgentRequestLaunchConfirmationTracker(
    private val latestAcknowledgedLaunchId: MutableStateFlow<Long> = MutableStateFlow(0L),
    private val nextLaunchId: AtomicLong = AtomicLong(),
) {
    fun nextLaunchId(): Long = nextLaunchId.incrementAndGet()

    fun acknowledge(launchId: Long?) {
        val confirmedLaunchId = launchId
            ?.takeIf { it > 0L }
            ?: return
        latestAcknowledgedLaunchId.update { currentLaunchId ->
            maxOf(currentLaunchId, confirmedLaunchId)
        }
    }

    suspend fun awaitLaunch(
        launchId: Long,
        timeoutMillis: Long,
    ): Boolean = withTimeoutOrNull(timeoutMillis) {
        if (latestAcknowledgedLaunchId.value >= launchId) {
            return@withTimeoutOrNull true
        }

        latestAcknowledgedLaunchId
            .filter { acknowledgedLaunchId ->
                acknowledgedLaunchId >= launchId
            }
            .first()
        true
    } ?: false
}
