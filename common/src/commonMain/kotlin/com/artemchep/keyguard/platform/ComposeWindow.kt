package com.artemchep.keyguard.platform

import androidx.compose.runtime.staticCompositionLocalOf

val LocalWindowId = staticCompositionLocalOf<WindowId> {
    WindowId(0L)
}
