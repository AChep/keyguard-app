package com.artemchep.keyguard.ui.theme

import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import com.artemchep.keyguard.common.model.AppColors
import com.artemchep.keyguard.common.model.AppFont
import com.artemchep.keyguard.common.model.AppTheme
import com.artemchep.keyguard.common.usecase.GetColors
import com.artemchep.keyguard.common.usecase.GetFont
import com.artemchep.keyguard.common.usecase.GetTheme
import com.artemchep.keyguard.common.usecase.GetThemeUseAmoledDark
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.theme.hasDarkThemeEnabled
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.theme.m3.dynamicColorScheme
import org.jetbrains.compose.resources.Font
import org.kodein.di.compose.rememberInstance

val ColorScheme.selectedContainer
    @ReadOnlyComposable
    get() = secondaryContainer

val ColorScheme.onSelectedContainer
    @ReadOnlyComposable
    get() = onSecondaryContainer

val ColorScheme.badgeContainer
    @ReadOnlyComposable
    get() = tertiaryContainer

private const val HUE_ERROR = 0f
private const val HUE_WARNING = 55f
private const val HUE_INFO = 210f
private const val HUE_OK = 120f

/**
 * Returns `true` if the color scheme is dark or
 * black, `false` otherwise.
 */
val ColorScheme.isDark
    @ReadOnlyComposable
    get() = background.luminance() < 0.5f

inline val ColorScheme.searchHighlightBackgroundColor get() = tertiaryContainer

inline val ColorScheme.searchHighlightContentColor get() = onTertiaryContainer

// We use this property to replace the error
// of the color scheme.
@Suppress("ObjectPropertyName")
private val ColorScheme._error: Color
    @Composable
    get() = buildContentColor(HUE_ERROR)

val ColorScheme.warning: Color
    @Composable
    get() = buildContentColor(HUE_WARNING)

val ColorScheme.warningVariant: Color
    @Composable
    get() = warning

val ColorScheme.info: Color
    @Composable
    get() = buildContentColor(HUE_INFO)

val ColorScheme.infoVariant: Color
    @Composable
    get() = info

val ColorScheme.ok: Color
    @Composable
    get() = buildContentColor(HUE_OK)

val ColorScheme.okVariant: Color
    @Composable
    get() = ok

@Composable
private fun ColorScheme.buildContentColor(
    hue: Float,
): Color {
    val isDark = isDark
    val color = remember(isDark) {
        buildContentColor(
            hue = hue,
            onDark = isDark,
        )
    }
    return color
}

private fun buildContentColor(
    hue: Float,
    onDark: Boolean,
): Color {
    val saturation: Float
    val lightness: Float
    if (onDark) {
        saturation = 0.72f // saturation
        lightness = 0.86f // value
    } else {
        saturation = 0.96f // saturation
        lightness = 0.80f // value
    }
    return Color.hsv(
        hue = hue,
        saturation = saturation,
        value = lightness,
    )
}

// We use this property to replace the error container
// of the color scheme.
@Suppress("ObjectPropertyName")
private val ColorScheme._errorContainer: Color
    @Composable
    get() = buildContainerColor(HUE_ERROR)

// We use this property to replace the error container
// of the color scheme.
@Suppress("ObjectPropertyName")
private val ColorScheme._onErrorContainer: Color
    @Composable
    get() = onSurfaceVariant

val ColorScheme.warningContainer: Color
    @Composable
    get() = buildContainerColor(HUE_WARNING)

val ColorScheme.warningContainerVariant: Color
    @Composable
    get() = buildContainerColor(HUE_WARNING)

val ColorScheme.onWarningContainer
    @Composable
    get() = buildOnContainerColor(HUE_WARNING)

val ColorScheme.infoContainer: Color
    @Composable
    get() = buildContainerColor(HUE_INFO)

val ColorScheme.infoContainerVariant: Color
    @Composable
    get() = buildContainerColor(HUE_INFO)

val ColorScheme.onInfoContainer
    @Composable
    get() = buildOnContainerColor(HUE_INFO)

val ColorScheme.okContainer: Color
    @Composable
    get() = buildContainerColor(HUE_OK)

val ColorScheme.okContainerVariant: Color
    @Composable
    get() = buildContainerColor(HUE_OK)

val ColorScheme.onOkContainer
    @Composable
    get() = buildOnContainerColor(HUE_OK)

@Composable
private fun ColorScheme.buildContainerColor(
    hue: Float,
): Color {
    val tint = Color.hsv(
        hue = hue,
        saturation = 1f,
        value = 1f,
    )
    val backgroundColor = if (isDark) {
        surfaceVariant
    } else {
        surfaceVariant
    }
    return tint
        .copy(alpha = 0.20f)
        .compositeOver(backgroundColor)
}

@Composable
private fun ColorScheme.buildOnContainerColor(
    hue: Float,
): Color {
    val isDark = isDark
    val color = remember(isDark) {
        buildOnContainerColor(
            hue = hue,
            onDark = isDark,
        )
    }
    return color
}

private fun buildOnContainerColor(
    hue: Float,
    onDark: Boolean,
): Color {
    val saturation: Float
    val lightness: Float
    if (onDark) {
        saturation = 0.1f // saturation
        lightness = 0.9f // value
    } else {
        saturation = 0.2f // saturation
        lightness = 0.2f // value
    }
    return Color.hsv(
        hue = hue,
        saturation = saturation,
        value = lightness,
    )
}

val monoFontFamily: FontFamily
    @Composable
    get() = robotoMonoFontFamily

val sansFontFamily: FontFamily
    @Composable
    get() = robotoSansFontFamily

val robotoMonoFontFamily: FontFamily
    @Composable
    get() = FontFamily(Font(Res.font.RobotoMono))

val robotoSansFontFamily: FontFamily
    @Composable
    get() {
        val getFont by rememberInstance<GetFont>()
        val font = remember(getFont) {
            getFont()
        }.collectAsState(null)
        val res = when (font.value) {
            AppFont.ROBOTO -> Res.font.Roboto_Regular
            AppFont.NOTO -> Res.font.NotoSans_Regular
            AppFont.ATKINSON_HYPERLEGIBLE -> Res.font.AtkinsonHyperlegible_Regular
            null -> Res.font.Roboto_Regular
        }
        return FontFamily(Font(res))
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KeyguardTheme(
    content: @Composable () -> Unit,
) {
    val getTheme by rememberInstance<GetTheme>()
    val getThemeUseAmoledDark by rememberInstance<GetThemeUseAmoledDark>()
    val getColors by rememberInstance<GetColors>()
    val theme by remember(getTheme) {
        getTheme()
    }.collectAsState(initial = null)
    val themeBlack by remember(getThemeUseAmoledDark) {
        getThemeUseAmoledDark()
    }.collectAsState(initial = false)
    val colors by remember(getColors) {
        getColors()
    }.collectAsState(initial = null)
    val isDarkColorScheme = when (theme) {
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
        null -> CurrentPlatform.hasDarkThemeEnabled()
    }

    val scheme = kotlin.run {
        val scheme = appColorScheme(
            colors = colors,
            isDarkColorScheme = isDarkColorScheme,
        )
        scheme.copy(
            errorContainer = scheme._errorContainer,
            onErrorContainer = scheme._onErrorContainer,
            error = scheme._error,
            background = if (themeBlack && isDarkColorScheme) Color.Black else scheme.background,
            surface = if (themeBlack && isDarkColorScheme) Color.Black else scheme.surface,
            surfaceContainerLowest = if (themeBlack && isDarkColorScheme) Color.Black else scheme.surfaceContainerLowest,
            surfaceContainerLow = if (themeBlack && isDarkColorScheme) Color.Black else scheme.surfaceContainerLow,
            surfaceContainer = if (themeBlack && isDarkColorScheme) scheme.surfaceContainerLow else scheme.surfaceContainer,
            surfaceContainerHigh = if (themeBlack && isDarkColorScheme) scheme.surfaceContainerLow else scheme.surfaceContainerHigh,
            surfaceContainerHighest = if (themeBlack && isDarkColorScheme) scheme.surfaceContainer else scheme.surfaceContainerHighest,
        )
    }

    val getFont by rememberInstance<GetFont>()
    val font = remember(getFont) {
        getFont()
    }.collectAsState(null)

    val typography = if (font.value == null) {
        Typography()
    } else {
        val defaultTypography = Typography()
        Typography(
            displayLarge = defaultTypography.displayLarge.copy(fontFamily = sansFontFamily),
            displayMedium = defaultTypography.displayMedium.copy(fontFamily = sansFontFamily),
            displaySmall = defaultTypography.displaySmall.copy(fontFamily = sansFontFamily),

            headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = sansFontFamily),
            headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = sansFontFamily),
            headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = sansFontFamily),

            titleLarge = defaultTypography.titleLarge.copy(fontFamily = sansFontFamily),
            titleMedium = defaultTypography.titleMedium.copy(fontFamily = sansFontFamily),
            titleSmall = defaultTypography.titleSmall.copy(fontFamily = sansFontFamily),

            bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = sansFontFamily),
            bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = sansFontFamily),
            bodySmall = defaultTypography.bodySmall.copy(fontFamily = sansFontFamily),

            labelLarge = defaultTypography.labelLarge.copy(fontFamily = sansFontFamily),
            labelMedium = defaultTypography.labelMedium.copy(fontFamily = sansFontFamily),
            labelSmall = defaultTypography.labelSmall.copy(fontFamily = sansFontFamily),
        )
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        typography = typography,
    ) {
        SystemUiThemeEffect()

        androidx.compose.material.MaterialTheme(
            colors = (if (isDarkColorScheme) darkColors() else lightColors())
                .copy(
                    primary = scheme.primary,
                    onPrimary = scheme.onPrimary,
                    secondary = scheme.secondary,
                    onSecondary = scheme.onSecondary,
                ),
        ) {
            content()
        }
    }
}

@Composable
private fun appColorScheme(
    colors: AppColors?,
    isDarkColorScheme: Boolean,
): ColorScheme = colors?.let { c ->
    runCatching {
        dynamicColorScheme(
            keyColor = Color(c.color),
            isDark = isDarkColorScheme,
        )
    }.getOrNull()
} ?: run {
    if (isDarkColorScheme) {
        appDynamicDarkColorScheme()
    } else {
        appDynamicLightColorScheme()
    }
}

@Composable
expect fun appDynamicDarkColorScheme(): ColorScheme

@Composable
expect fun appDynamicLightColorScheme(): ColorScheme

@Composable
expect fun SystemUiThemeEffect()

fun plainDarkColorScheme() = darkColorScheme(
    surfaceContainer = Color(red = 32, green = 33, blue = 33),
    surfaceContainerHigh = Color(red = 43, green = 43, blue = 44),
    surfaceContainerHighest = Color(red = 54, green = 54, blue = 54),
    surfaceContainerLow = Color(red = 27, green = 27, blue = 27),
    surfaceContainerLowest = Color(red = 20, green = 22, blue = 22),
    surfaceTint = Color(red = 250, green = 250, blue = 250),
    surface = Color(red = 20, green = 22, blue = 22),
    background = Color(red = 20, green = 22, blue = 22),
)

fun plainLightColorScheme() = lightColorScheme(
    surfaceContainer = Color(red = 245, green = 245, blue = 245),
    surfaceContainerHigh = Color(red = 238, green = 240, blue = 240),
    surfaceContainerHighest = Color(red = 222, green = 224, blue = 225),
    surfaceContainerLow = Color(red = 250, green = 250, blue = 250),
    surfaceContainerLowest = Color(red = 255, green = 255, blue = 255),
    surfaceTint = Color(red = 27, green = 27, blue = 27),
    surface = Color(red = 255, green = 255, blue = 255),
    background = Color(red = 255, green = 255, blue = 255),
)
