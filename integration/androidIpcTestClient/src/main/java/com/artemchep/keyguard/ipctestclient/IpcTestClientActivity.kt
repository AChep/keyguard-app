package com.artemchep.keyguard.ipctestclient

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.LinkedBlockingQueue

/**
 * Headless host for a provider approval [PendingIntent].
 *
 * The provider hands the client an approval [PendingIntent] that has to be
 * started for result, which only an activity can do. This activity draws
 * nothing; it exists to own that result channel and to publish the outcome on
 * [results], which the instrumentation suite drains.
 */
class IpcTestClientActivity : ComponentActivity() {
    class Result(
        val resultCode: Int,
        val data: Intent?,
    )

    companion object {
        val results = LinkedBlockingQueue<Result>()
    }

    private val approvalLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        // Deliberately does not finish itself: the instrumentation suite owns
        // this activity's lifetime through ActivityScenario, and finishing here
        // races that teardown.
        results.offer(Result(result.resultCode, result.data))
    }

    fun launchApproval(pendingIntent: PendingIntent) {
        launchApproval(pendingIntent.intentSender)
    }

    fun launchApproval(intentSender: IntentSender) {
        approvalLauncher.launch(
            IntentSenderRequest.Builder(intentSender).build(),
        )
    }
}
