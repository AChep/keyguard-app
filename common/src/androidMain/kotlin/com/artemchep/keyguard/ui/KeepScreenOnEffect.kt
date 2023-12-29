package com.artemchep.keyguard.ui

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.artemchep.keyguard.android.closestActivityOrNull

@Composable
actual fun KeepScreenOnEffect() {
    val context = LocalContext.current
    val window = remember(context) {
        val activity = context.closestActivityOrNull
        activity?.window
    }

    DisposableEffect(window) {
        val windowFlag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        window?.addFlags(windowFlag)

        onDispose {
            window?.clearFlags(windowFlag)
        }
    }
}
