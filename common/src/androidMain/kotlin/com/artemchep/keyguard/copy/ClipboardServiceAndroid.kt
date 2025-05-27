package com.artemchep.keyguard.copy

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.getSystemService
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.platform.CurrentPlatformImpl
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ClipboardServiceAndroid(
    private val application: Application,
    private val clipboardManager: ClipboardManager,
) : ClipboardService {
    constructor(
        directDI: DirectDI,
    ) : this(
        application = directDI.instance<Application>(),
        clipboardManager = directDI.instance<Application>().getSystemService<ClipboardManager>()!!,
    )

    override fun setPrimaryClip(value: String, concealed: Boolean) {
        val clip =
            ClipData.newPlainText(null, value) // When your app targets API level 33 or higher
        clip.apply {
            description.extras = PersistableBundle().apply {
                // Do not show the plain value if it
                // should be concealed.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, concealed)
                }
            }
        }
        clipboardManager.setPrimaryClip(clip)
    }

    override fun clearPrimaryClip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            clipboardManager.clearPrimaryClip()
            return
        }

        val clip =
            ClipData.newPlainText(null, "")
        clipboardManager.setPrimaryClip(clip)
    }

    // Chromebooks do not show the copy notification, at least for now, therefore
    // we want to show the copy notification for those devices.
    override fun hasCopyNotification() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !CurrentPlatformImpl.isChromebook
}
