package com.artemchep.keyguard.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.artemchep.keyguard.Main
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AllowScreenshots
import com.artemchep.keyguard.common.usecase.GetAllowScreenshots
import com.artemchep.keyguard.common.usecase.PutAllowScreenshots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance

class BenchmarkScreenRecordingReceiver : BroadcastReceiver() {
    private companion object {
        const val ACTION_ENABLE_SCREEN_RECORDING =
            "com.artemchep.keyguard.benchmark.ENABLE_SCREEN_RECORDING"

        const val TAG = "BenchmarkScreenCapture"

        const val PREFERENCE_TIMEOUT_MS = 10_000L
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_ENABLE_SCREEN_RECORDING) {
            resultCode = Activity.RESULT_CANCELED
            resultData = "Unsupported action '${intent.action}'."
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val directDi = (context.applicationContext as Main).di.direct
                directDi.instance<PutAllowScreenshots>()(AllowScreenshots.LIMITED).bind()
                withTimeout(PREFERENCE_TIMEOUT_MS) {
                    directDi.instance<GetAllowScreenshots>()()
                        .first { value -> value == AllowScreenshots.LIMITED }
                }
            }.onSuccess { value ->
                pendingResult.resultCode = Activity.RESULT_OK
                pendingResult.resultData = value.key
            }.onFailure { error ->
                Log.e(TAG, "Could not enable screen recording for benchmarks.", error)
                pendingResult.resultCode = Activity.RESULT_CANCELED
                pendingResult.resultData = "${error::class.simpleName}:${error.message}"
            }
            pendingResult.finish()
        }
    }
}
