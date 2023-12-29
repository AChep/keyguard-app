package com.artemchep.keyguard.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow

val LocalComposeWindow = staticCompositionLocalOf<ComposeWindow> {
    throw IllegalStateException()
}
