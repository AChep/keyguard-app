package com.artemchep.keyguard.android.ui

import android.app.Activity
import android.os.Build

/**
 * Hides every overlay window owned by another app for as long as this window
 * is visible.
 */
internal fun Activity.hideOverlayWindows() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        window?.setHideOverlayWindows(true)
    }
}
