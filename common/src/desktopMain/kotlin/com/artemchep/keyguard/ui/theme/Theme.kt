@file:JvmName("PlatformTheme")

package com.artemchep.keyguard.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import com.artemchep.keyguard.ui.LocalComposeWindow
import io.github.kdroidfilter.platformtools.darkmodedetector.windows.setWindowsAdaptiveTitleBar

@Composable
@NonRestartableComposable
actual fun appDynamicDarkColorScheme(): ColorScheme = plainDarkColorScheme()

@Composable
@NonRestartableComposable
actual fun appDynamicLightColorScheme(): ColorScheme = plainLightColorScheme()

@Composable
actual fun SystemUiThemeEffect() {
    val dark = MaterialTheme.colorScheme.isDark
    LocalComposeWindow.current.setWindowsAdaptiveTitleBar(dark)
}
