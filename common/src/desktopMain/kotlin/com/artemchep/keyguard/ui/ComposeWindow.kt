package com.artemchep.keyguard.ui

import androidx.compose.runtime.staticCompositionLocalOf
import java.awt.Window

val LocalComposeWindow = staticCompositionLocalOf<Window> {
    throw IllegalStateException()
}
