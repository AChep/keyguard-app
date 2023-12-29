package com.artemchep.keyguard.platform

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

actual val LocalAnimationFactor: Float
    @Composable
    get() {
        val context = LocalContext.current
        val scale = remember(context) {
            try {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.TRANSITION_ANIMATION_SCALE,
                )
            } catch (e: Settings.SettingNotFoundException) {
                1f
            }
        }
        return scale
    }
