package com.artemchep.keyguard.ui.theme.m3

import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.ui.graphics.Color

fun lightColorScheme(
    primary: Color = ColorLight.Primary,
    onPrimary: Color = ColorLight.OnPrimary,
    primaryContainer: Color = ColorLight.PrimaryContainer,
    onPrimaryContainer: Color = ColorLight.OnPrimaryContainer,
    inversePrimary: Color = ColorLight.InversePrimary,
    secondary: Color = ColorLight.Secondary,
    onSecondary: Color = ColorLight.OnSecondary,
    secondaryContainer: Color = ColorLight.SecondaryContainer,
    onSecondaryContainer: Color = ColorLight.OnSecondaryContainer,
    tertiary: Color = ColorLight.Tertiary,
    onTertiary: Color = ColorLight.OnTertiary,
    tertiaryContainer: Color = ColorLight.TertiaryContainer,
    onTertiaryContainer: Color = ColorLight.OnTertiaryContainer,
    background: Color = ColorLight.Background,
    onBackground: Color = ColorLight.OnBackground,
    surface: Color = ColorLight.Surface,
    onSurface: Color = ColorLight.OnSurface,
    surfaceVariant: Color = ColorLight.SurfaceVariant,
    onSurfaceVariant: Color = ColorLight.OnSurfaceVariant,
    inverseSurface: Color = ColorLight.InverseSurface,
    inverseOnSurface: Color = ColorLight.InverseOnSurface,
    error: Color = ColorLight.Error,
    onError: Color = ColorLight.OnError,
    errorContainer: Color = ColorLight.ErrorContainer,
    onErrorContainer: Color = ColorLight.OnErrorContainer,
    outline: Color = ColorLight.Outline,
): Colors =
    lightColors(
        primary = primary,
        onPrimary = onPrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        error = error,
        onError = onError,
    )

/**
 * Returns a dark Material color scheme.
 */
fun darkColorScheme(
    primary: Color = ColorDark.Primary,
    onPrimary: Color = ColorDark.OnPrimary,
    primaryContainer: Color = ColorDark.PrimaryContainer,
    onPrimaryContainer: Color = ColorDark.OnPrimaryContainer,
    inversePrimary: Color = ColorDark.InversePrimary,
    secondary: Color = ColorDark.Secondary,
    onSecondary: Color = ColorDark.OnSecondary,
    secondaryContainer: Color = ColorDark.SecondaryContainer,
    onSecondaryContainer: Color = ColorDark.OnSecondaryContainer,
    tertiary: Color = ColorDark.Tertiary,
    onTertiary: Color = ColorDark.OnTertiary,
    tertiaryContainer: Color = ColorDark.TertiaryContainer,
    onTertiaryContainer: Color = ColorDark.OnTertiaryContainer,
    background: Color = ColorDark.Background,
    onBackground: Color = ColorDark.OnBackground,
    surface: Color = ColorDark.Surface,
    onSurface: Color = ColorDark.OnSurface,
    surfaceVariant: Color = ColorDark.SurfaceVariant,
    onSurfaceVariant: Color = ColorDark.OnSurfaceVariant,
    inverseSurface: Color = ColorDark.InverseSurface,
    inverseOnSurface: Color = ColorDark.InverseOnSurface,
    error: Color = ColorDark.Error,
    onError: Color = ColorDark.OnError,
    errorContainer: Color = ColorDark.ErrorContainer,
    onErrorContainer: Color = ColorDark.OnErrorContainer,
    outline: Color = ColorDark.Outline,
): Colors =
    darkColors(
        primary = primary,
        onPrimary = onPrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        error = error,
        onError = onError,
    )

internal object ColorLight {
    val Background = Palette.Neutral95
    val Error = Palette.Error40
    val ErrorContainer = Palette.Error90
    val InverseOnSurface = Palette.Neutral95
    val InversePrimary = Palette.Primary80
    val InverseSurface = Palette.Neutral20
    val OnBackground = Palette.Neutral10
    val OnError = Palette.Error100
    val OnErrorContainer = Palette.Error10
    val OnPrimary = Palette.Primary100
    val OnPrimaryContainer = Palette.Primary10
    val OnSecondary = Palette.Secondary100
    val OnSecondaryContainer = Palette.Secondary10
    val OnSurface = Palette.Neutral10
    val OnSurfaceVariant = Palette.NeutralVariant30
    val OnTertiary = Palette.Tertiary100
    val OnTertiaryContainer = Palette.Tertiary10
    val Outline = Palette.NeutralVariant50
    val Primary = Palette.Primary40
    val PrimaryContainer = Palette.Primary90
    val Secondary = Palette.Secondary40
    val SecondaryContainer = Palette.Secondary90
    val Surface = Palette.Neutral99
    val SurfaceVariant = Palette.NeutralVariant90
    val Tertiary = Palette.Tertiary40
    val TertiaryContainer = Palette.Tertiary90
}

internal object ColorDark {
    val Background = Palette.Neutral10
    val Error = Palette.Error80
    val ErrorContainer = Palette.Error30
    val InverseOnSurface = Palette.Neutral20
    val InversePrimary = Palette.Primary40
    val InverseSurface = Palette.Neutral90
    val OnBackground = Palette.Neutral90
    val OnError = Palette.Error20
    val OnErrorContainer = Palette.Error80
    val OnPrimary = Palette.Primary20
    val OnPrimaryContainer = Palette.Primary90
    val OnSecondary = Palette.Secondary20
    val OnSecondaryContainer = Palette.Secondary90
    val OnSurface = Palette.Neutral90
    val OnSurfaceVariant = Palette.NeutralVariant80
    val OnTertiary = Palette.Tertiary20
    val OnTertiaryContainer = Palette.Tertiary90
    val Outline = Palette.NeutralVariant60
    val Primary = Palette.Primary80
    val PrimaryContainer = Palette.Primary30
    val Secondary = Palette.Secondary80
    val SecondaryContainer = Palette.Secondary30
    val Surface = Palette.Neutral10
    val SurfaceVariant = Palette.NeutralVariant30
    val Tertiary = Palette.Tertiary80
    val TertiaryContainer = Palette.Tertiary30
}
