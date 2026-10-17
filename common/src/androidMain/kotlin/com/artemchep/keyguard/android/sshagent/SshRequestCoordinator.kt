package com.artemchep.keyguard.android.sshagent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.artemchep.keyguard.common.service.agent.AgentApprovalBroker
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalRequest
import com.artemchep.keyguard.feature.agent.AgentRequestUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

internal object SshRequestCoordinator {
    private const val TAG = "SshRequestCoordinator"
    private const val LAUNCH_CONFIRMATION_TIMEOUT_MS = 750L

    private val launchConfirmationTracker = SshRequestLaunchConfirmationTracker()

    /** The SSH signing requests that wait for the user, shared by every agent bridge. */
    val approvals = AgentApprovalBroker<SshAgentApprovalRequest>(idOf = { it.id })

    /** The open request prompts; a request without one falls back to its notification. */
    val prompts = SshRequestPromptTracker()

    /** Denies the request the dismissed notification [notificationTag] represents. */
    fun dismissRequest(notificationTag: String) {
        val request = approvals.pending.value
            .firstOrNull { it.notificationTag == notificationTag }
            ?: return
        approvals.respond(request.id, false)
    }

    fun confirmLaunch(intent: Intent?) {
        launchConfirmationTracker.acknowledge(
            launchId = SshRequestActivity.getLaunchId(intent),
        )
    }

    suspend fun show(context: Context): Boolean {
        val launchId = launchConfirmationTracker.nextLaunchId()
        val intent = SshRequestActivity.getIntent(
            context = context,
            launchId = launchId,
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        val started = withContext(Dispatchers.Main.immediate) {
            runCatching {
                Log.i(TAG, "Starting the SSH request activity launchId=$launchId")
                context.startActivity(intent)
            }.onFailure { e ->
                Log.w(TAG, "Unable to show SSH request activity launchId=$launchId", e)
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
            val msg = "SSH request activity launch attempted but not confirmed " +
                    "launchId=$launchId timeoutMs=$LAUNCH_CONFIRMATION_TIMEOUT_MS"
            Log.w(TAG, msg)
        }
        return confirmed
    }
}

/**
 * Denies the [shown] request the user dismissed, if the prompt shows one.
 * Returns whether the prompt may close: the requests that wait behind the
 * dismissed one still need an answer.
 */
internal fun AgentApprovalBroker<*>.dismissPrompt(
    shown: AgentRequestUiState<*>?,
): Boolean {
    shown?.onDecision?.invoke(false, "activity_dismissed")
    return pending.value.isEmpty()
}

/** The open request prompts, by a token that a prompt keeps across configuration changes. */
internal class SshRequestPromptTracker {
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

internal class SshRequestLaunchConfirmationTracker(
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
