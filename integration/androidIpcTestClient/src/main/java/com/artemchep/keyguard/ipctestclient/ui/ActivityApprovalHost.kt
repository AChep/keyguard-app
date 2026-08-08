package com.artemchep.keyguard.ipctestclient.ui

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.artemchep.keyguard.ipctestclient.ipc.ApprovalHost
import com.artemchep.keyguard.ipctestclient.ipc.ApprovalOutcome
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Starts the approval dialog from the driver activity and blocks the calling
 * (background) thread until the user answers.
 *
 * Must be constructed while the activity is still being created; an
 * `ActivityResultLauncher` cannot be registered after it has started.
 */
class ActivityApprovalHost(
    private val activity: ComponentActivity,
) : ApprovalHost {
    private val results = LinkedBlockingQueue<ApprovalOutcome>()

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        results.offer(ApprovalOutcome(result.resultCode, result.data))
    }

    override fun launch(
        pendingIntent: PendingIntent,
        timeoutMs: Long,
    ): ApprovalOutcome {
        results.clear()
        activity.runOnUiThread {
            launcher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
            )
        }
        return results.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: ApprovalOutcome(Activity.RESULT_CANCELED, null)
    }
}
