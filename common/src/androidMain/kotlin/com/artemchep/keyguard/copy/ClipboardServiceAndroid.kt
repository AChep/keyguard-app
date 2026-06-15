package com.artemchep.keyguard.copy

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.getSystemService
import com.artemchep.keyguard.android.clipboard.ClipboardClearWorker
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.GetClipboardAutoClear
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.platform.CurrentPlatformImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

class ClipboardServiceAndroid(
    private val application: Application,
    private val clipboardManager: ClipboardManager,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val getClipboardAutoClear: GetClipboardAutoClear,
) : ClipboardService {
    constructor(
        directDI: DirectDI,
    ) : this(
        application = directDI.instance<Application>(),
        clipboardManager = directDI.instance<Application>().getSystemService<ClipboardManager>()!!,
        windowCoroutineScope = directDI.instance(),
        getClipboardAutoClear = directDI.instance(),
    )

    private val autoClearMutex = Mutex()

    override fun setPrimaryClip(value: String, concealed: Boolean) {
        val clip =
            ClipData.newPlainText(null, value) // When your app targets API level 33 or higher
        clip.apply {
            description.extras = PersistableBundle().apply {
                // Do not show the plain value if it
                // should be concealed.
                val extraIsSensitiveKey =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ClipDescription.EXTRA_IS_SENSITIVE
                    } else {
                        // Some of the OS versions do still check for the
                        // extra, namely:
                        // https://github.com/AChep/keyguard-app/issues/1239
                        "android.content.extra.IS_SENSITIVE"
                    }
                putBoolean(extraIsSensitiveKey, concealed)
            }
        }
        clipboardManager.setPrimaryClip(clip)
        scheduleAutoClear()
    }

    override fun clearPrimaryClip() {
        cancelAutoClear()
        clearPrimaryClip(clipboardManager)
    }

    private fun cancelAutoClear() = launchAutoClearMutex {
        internalCancelAutoClear()
    }

    private fun scheduleAutoClear() = launchAutoClearMutex {
        val duration = getClipboardAutoClear()
            .first()
        internalScheduleAutoClear(
            duration = duration,
        )
    }

    private fun launchAutoClearMutex(
        block: suspend () -> Unit,
    ) {
        windowCoroutineScope.launch {
            autoClearMutex.withLock {
                block()
            }
        }
    }

    private fun internalCancelAutoClear() {
        ClipboardClearWorker.cancel(application)
    }

    private fun internalScheduleAutoClear(
        duration: Duration,
    ) {
        if (duration == Duration.INFINITE) {
            cancelAutoClear()
            return
        }

        if (!duration.isPositive()) {
            clearPrimaryClip()
            return
        }

        ClipboardClearWorker.enqueue(
            context = application,
            delay = duration,
        )
    }

    // Chromebooks do not show the copy notification, at least for now, therefore
    // we want to show the copy notification for those devices.
    override fun hasCopyNotification() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !CurrentPlatformImpl.isChromebook
}

internal fun clearPrimaryClip(
    clipboardManager: ClipboardManager,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        clipboardManager.clearPrimaryClip()
        return
    }

    val clip =
        ClipData.newPlainText(null, "")
    clipboardManager.setPrimaryClip(clip)
}
