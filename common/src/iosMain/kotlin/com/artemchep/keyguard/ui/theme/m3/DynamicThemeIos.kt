package com.artemchep.keyguard.ui.theme.m3

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.ui.theme.plainDarkColorScheme
import com.artemchep.keyguard.ui.theme.plainLightColorScheme

@Stable
actual fun dynamicColorScheme(
    keyColor: Color,
    isDark: Boolean,
    contrastLevel: Double,
): ColorScheme = if (isDark) {
    plainDarkColorScheme()
} else {
    plainLightColorScheme()
}
