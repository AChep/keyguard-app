package com.artemchep.keyguard.ui.theme.m3

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.artemchep.keyguard.ui.theme.plainDarkColorScheme
import com.artemchep.keyguard.ui.theme.plainLightColorScheme
import com.kyant.m3color.hct.Hct
import com.kyant.m3color.scheme.SchemeExpressive
import com.kyant.m3color.scheme.SchemeVibrant

@Stable
fun dynamicColorScheme(
    keyColor: Color,
    isDark: Boolean,
    contrastLevel: Double = 0.0,
): ColorScheme {
    val hct = Hct.fromInt(keyColor.toArgb())
    val scheme = SchemeVibrant(hct, isDark, contrastLevel)

    val base = if (isDark) plainDarkColorScheme() else plainLightColorScheme()
    return base.copy(
        error = scheme.error.toColor(),
        errorContainer = scheme.errorContainer.toColor(),
        inverseOnSurface = scheme.inverseOnSurface.toColor(),
        inversePrimary = scheme.inversePrimary.toColor(),
        inverseSurface = scheme.inverseSurface.toColor(),
        onError = scheme.onError.toColor(),
        onErrorContainer = scheme.onErrorContainer.toColor(),
        onPrimary = scheme.onPrimary.toColor(),
        onPrimaryContainer = scheme.onPrimaryContainer.toColor(),
        onSecondary = scheme.onSecondary.toColor(),
        onSecondaryContainer = scheme.onSecondaryContainer.toColor(),
        onSurfaceVariant = scheme.onSurfaceVariant.toColor(),
        onTertiary = scheme.onTertiary.toColor(),
        onTertiaryContainer = scheme.onTertiaryContainer.toColor(),
        outline = scheme.outline.toColor(),
        outlineVariant = scheme.outlineVariant.toColor(),
        primary = scheme.primary.toColor(),
        primaryContainer = scheme.primaryContainer.toColor(),
        scrim = scheme.scrim.toColor(),
        secondary = scheme.secondary.toColor(),
        secondaryContainer = scheme.secondaryContainer.toColor(),
        surfaceDim = scheme.surfaceDim.toColor(),
        surfaceVariant = scheme.surfaceVariant.toColor(),
        tertiary = scheme.tertiary.toColor(),
        tertiaryContainer = scheme.tertiaryContainer.toColor(),
    )
}

@Suppress("NOTHING_TO_INLINE")
private inline fun Int.toColor(): Color = Color(this)
