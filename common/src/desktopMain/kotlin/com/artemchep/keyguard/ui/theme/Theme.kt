@file:JvmName("PlatformTheme")

package com.artemchep.keyguard.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable

@Composable
@NonRestartableComposable
actual fun appDynamicDarkColorScheme(): ColorScheme = plainDarkColorScheme()

@Composable
@NonRestartableComposable
actual fun appDynamicLightColorScheme(): ColorScheme = plainLightColorScheme()

@Composable
actual fun SystemUiThemeEffect() {
    // Do nothing.
}
