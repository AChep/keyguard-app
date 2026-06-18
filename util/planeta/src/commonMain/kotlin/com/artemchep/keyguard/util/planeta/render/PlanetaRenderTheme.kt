package com.artemchep.keyguard.util.planeta.render

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.artemchep.keyguard.util.planeta.PlanetaOrbital
import com.artemchep.keyguard.util.planeta.PlanetaTheme

internal val PlanetaTheme.starColor: Color
    get() = when (this) {
        PlanetaTheme.Dark -> Color.White
        PlanetaTheme.Light -> Color(0xFF30445C)
    }

internal val PlanetaTheme.starAlphaMin: Float
    get() = when (this) {
        PlanetaTheme.Dark -> 0.12f
        PlanetaTheme.Light -> 0.045f
    }

internal val PlanetaTheme.starAlphaMax: Float
    get() = when (this) {
        PlanetaTheme.Dark -> 0.58f
        PlanetaTheme.Light -> 0.2f
    }

internal val PlanetaTheme.orbitTrailAlphaMultiplier: Float
    get() = when (this) {
        PlanetaTheme.Dark -> 1f
        PlanetaTheme.Light -> 0.82f
    }

internal val PlanetaTheme.ringAlphaMultiplier: Float
    get() = when (this) {
        PlanetaTheme.Dark -> 1f
        PlanetaTheme.Light -> 0.86f
    }

internal val PlanetaTheme.bloomInnerAlpha: Float
    get() = when (this) {
        PlanetaTheme.Dark -> 0.24f
        PlanetaTheme.Light -> 0.105f
    }

internal val PlanetaTheme.bloomOuterAlpha: Float
    get() = when (this) {
        PlanetaTheme.Dark -> 0.08f
        PlanetaTheme.Light -> 0.045f
    }

internal val PlanetaTheme.atmosphereAlphaMultiplier: Float
    get() = when (this) {
        PlanetaTheme.Dark -> 1f
        PlanetaTheme.Light -> 0.72f
    }

internal val PlanetaTheme.glowBlendMode: BlendMode
    get() = when (this) {
        PlanetaTheme.Dark -> BlendMode.Plus
        PlanetaTheme.Light -> BlendMode.SrcOver
    }

internal val PlanetaTheme.rimColor: Color
    get() = when (this) {
        PlanetaTheme.Dark -> Color.White.copy(alpha = 0.12f)
        PlanetaTheme.Light -> Color(0xFF26384C).copy(alpha = 0.18f)
    }

internal val PlanetaTheme.featureShadowColor: Color
    get() = when (this) {
        PlanetaTheme.Dark -> Color.Black
        PlanetaTheme.Light -> Color(0xFF25374A)
    }

internal val PlanetaTheme.featureShadowAlphaMultiplier: Float
    get() = when (this) {
        PlanetaTheme.Dark -> 0.16f
        PlanetaTheme.Light -> 0.11f
    }

internal val PlanetaTheme.terminatorColor: Color
    get() = when (this) {
        PlanetaTheme.Dark -> Color.Black
        PlanetaTheme.Light -> Color(0xFF182333)
    }

internal val PlanetaTheme.terminatorMidAlpha: Float
    get() = when (this) {
        PlanetaTheme.Dark -> 0.32f
        PlanetaTheme.Light -> 0.28f
    }

internal val PlanetaTheme.terminatorOuterAlpha: Float
    get() = when (this) {
        PlanetaTheme.Dark -> 0.72f
        PlanetaTheme.Light -> 0.46f
    }

internal val PlanetaTheme.specularColor: Color
    get() = when (this) {
        PlanetaTheme.Dark -> Color.White
        PlanetaTheme.Light -> Color(0xFFFFFCF2)
    }

internal val PlanetaTheme.specularOceanAlpha: Float
    get() = when (this) {
        PlanetaTheme.Dark -> 0.32f
        PlanetaTheme.Light -> 0.24f
    }

internal val PlanetaTheme.specularAlpha: Float
    get() = when (this) {
        PlanetaTheme.Dark -> 0.18f
        PlanetaTheme.Light -> 0.14f
    }

internal val PlanetaTheme.specularEdgeAlpha: Float
    get() = when (this) {
        PlanetaTheme.Dark -> 0.05f
        PlanetaTheme.Light -> 0.04f
    }

internal fun Color.forLightSurface(
    theme: PlanetaTheme,
    amount: Float,
): Color = when (theme) {
    PlanetaTheme.Dark -> this
    PlanetaTheme.Light -> lerp(this, Color(0xFF26384C), amount)
}

internal fun PlanetaOrbital.highlightColor(theme: PlanetaTheme): Color =
    when (theme) {
        PlanetaTheme.Dark -> Color.White.copy(alpha = 0.95f)
        PlanetaTheme.Light -> lerp(color, Color.White, 0.72f).copy(alpha = 0.92f)
    }
