package com.artemchep.keyguard.platform

import androidx.compose.runtime.staticCompositionLocalOf

val LocalWindowId = staticCompositionLocalOf<WindowId> {
    WindowId(0L)
}

val LocalWindowRev = staticCompositionLocalOf<WindowRev> {
    WindowRev(0L)
}
