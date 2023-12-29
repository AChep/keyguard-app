package com.artemchep.keyguard

import androidx.compose.runtime.staticCompositionLocalOf

val LocalAppMode = staticCompositionLocalOf<AppMode> {
    AppMode.Main
}
