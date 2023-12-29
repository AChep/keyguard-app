@file:JvmName("PlatformTheme")

package com.artemchep.keyguard.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable

@Composable
@NonRestartableComposable
actual fun appDynamicDarkColorScheme(): ColorScheme = darkColorScheme()

@Composable
@NonRestartableComposable
actual fun appDynamicLightColorScheme(): ColorScheme = lightColorScheme()

@Composable
actual fun SystemUiThemeEffect() {
    // Do nothing.
}
