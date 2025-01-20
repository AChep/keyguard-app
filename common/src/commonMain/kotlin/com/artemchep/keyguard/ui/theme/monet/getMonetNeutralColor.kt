package com.artemchep.keyguard.ui.theme.monet

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dev.kdrag0n.colorkt.conversion.ConversionGraph
import dev.kdrag0n.colorkt.rgb.Srgb
import com.artemchep.keyguard.ui.theme.monet.ColorScheme as MonetColorScheme

// TODO: Support properly populating all the difference surfaces
//  that were introduced.

/**
 *  To avoid editing the core Monet code by kdrag0n, these are extensions instead
 */
fun dev.kdrag0n.colorkt.Color.toArgb(): Int {
    val srgb = ConversionGraph.convert(this, Srgb::class) as Srgb
    return srgb.toRgb8() or (0xFF shl 24)
}

private fun MonetColorScheme.getMonetNeutralColor(
    type: Int,
    shade: Int,
): Color {
    val monetColor = when (type) {
        1 -> this.neutral1[shade]
        else -> this.neutral2[shade]
    }?.toArgb() ?: throw Exception("Neutral$type shade $shade doesn't exist")
    return Color(monetColor)
}

private fun MonetColorScheme.getMonetAccentColor(
    type: Int,
    shade: Int,
): Color {
    val monetColor = when (type) {
        1 -> this.accent1[shade]
        2 -> this.accent2[shade]
        else -> this.accent3[shade]
    }?.toArgb() ?: throw Exception("Accent$type shade $shade doesn't exist")
    return Color(monetColor)
}

/**
 * Any values that are not set will be chosen to best represent default values given by [dynamicLightColorScheme][androidx.compose.material3.dynamicLightColorScheme]
 * on Android 12+ devices
 */
fun MonetColorScheme.lightMonetCompatScheme(
    primary: Color = getMonetAccentColor(1, 700),
    onPrimary: Color = getMonetNeutralColor(1, 50),
    primaryContainer: Color = getMonetAccentColor(2, 100),
    onPrimaryContainer: Color = getMonetAccentColor(1, 900),
    inversePrimary: Color = getMonetAccentColor(1, 200),
    secondary: Color = getMonetAccentColor(2, 700),
    onSecondary: Color = getMonetNeutralColor(1, 50),
    secondaryContainer: Color = getMonetAccentColor(2, 100),
    onSecondaryContainer: Color = getMonetAccentColor(2, 900),
    tertiary: Color = getMonetAccentColor(3, 600),
    onTertiary: Color = getMonetNeutralColor(1, 50),
    tertiaryContainer: Color = getMonetAccentColor(3, 100),
    onTertiaryContainer: Color = getMonetAccentColor(3, 900),
    surfaceVariant: Color = getMonetNeutralColor(2, 100),
    onSurfaceVariant: Color = getMonetNeutralColor(2, 700),
    inverseSurface: Color = getMonetNeutralColor(1, 800),
    inverseOnSurface: Color = getMonetNeutralColor(2, 50),
    outline: Color = getMonetAccentColor(2, 500),
): ColorScheme = lightColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface,
    outline = outline,
)

/**
 * Any values that are not set will be chosen to best represent default values given by [dynamicDarkColorScheme][androidx.compose.material3.dynamicDarkColorScheme]
 * on Android 12+ devices
 */
fun MonetColorScheme.darkMonetCompatScheme(
    primary: Color = getMonetAccentColor(1, 200),
    onPrimary: Color = getMonetAccentColor(1, 800),
    primaryContainer: Color = getMonetAccentColor(1, 600),
    onPrimaryContainer: Color = getMonetAccentColor(2, 100),
    inversePrimary: Color = getMonetAccentColor(1, 600),
    secondary: Color = getMonetAccentColor(2, 200),
    onSecondary: Color = getMonetAccentColor(2, 800),
    secondaryContainer: Color = getMonetAccentColor(2, 700),
    onSecondaryContainer: Color = getMonetAccentColor(2, 100),
    tertiary: Color = getMonetAccentColor(3, 200),
    onTertiary: Color = getMonetAccentColor(3, 700),
    tertiaryContainer: Color = getMonetAccentColor(3, 700),
    onTertiaryContainer: Color = getMonetAccentColor(3, 100),
    surfaceVariant: Color = getMonetNeutralColor(2, 700),
    onSurfaceVariant: Color = getMonetNeutralColor(2, 200),
    inverseSurface: Color = getMonetNeutralColor(1, 100),
    inverseOnSurface: Color = getMonetNeutralColor(1, 800),
    outline: Color = getMonetNeutralColor(2, 500),
): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface,
    outline = outline,
)
