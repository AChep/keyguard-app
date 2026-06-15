package com.artemchep.keyguard.android.sshagent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

internal object SshRequestCoordinator {
    private const val TAG = "SshRequestCoordinator"
    private const val LAUNCH_CONFIRMATION_TIMEOUT_MS = 750L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val requestQueue = SshAgentRequestQueue(scope)
    private val launchConfirmationTracker = SshRequestLaunchConfirmationTracker()

    val state: StateFlow<SshAgentRequestQueue.ActiveRequestState?>
        get() = requestQueue.state

    suspend fun enqueue(request: SshAgentRequest) {
        requestQueue.enqueue(request)
    }

    fun dismissCurrentRequest() {
        requestQueue.dismissCurrentRequest()
    }

    fun dismissRequest(notificationTag: String) {
        requestQueue.dismissRequest(notificationTag)
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
