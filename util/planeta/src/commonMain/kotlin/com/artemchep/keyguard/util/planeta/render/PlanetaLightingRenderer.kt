package com.artemchep.keyguard.util.planeta.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.artemchep.keyguard.util.planeta.PlanetaSpec
import com.artemchep.keyguard.util.planeta.PlanetaSurface
import com.artemchep.keyguard.util.planeta.PlanetaTheme
import com.artemchep.keyguard.util.planeta.internal.toRadians
import kotlin.math.cos
import kotlin.math.sin

internal fun DrawScope.drawPlanetBloom(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
    theme: PlanetaTheme,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color
                    .forLightSurface(theme, amount = 0.16f)
                    .copy(alpha = theme.bloomInnerAlpha * alpha),
                color
                    .forLightSurface(theme, amount = 0.22f)
                    .copy(alpha = theme.bloomOuterAlpha * alpha),
                Color.Transparent,
            ),
            center = center,
            radius = radius * 2.15f,
        ),
        radius = radius * 2.15f,
        center = center,
        blendMode = theme.glowBlendMode,
    )
}

internal fun DrawScope.drawAtmosphere(
    center: Offset,
    radius: Float,
    spec: PlanetaSpec,
    theme: PlanetaTheme,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                spec.palette.atmosphere
                    .forLightSurface(theme, amount = 0.18f)
                    .copy(alpha = 0.18f * theme.atmosphereAlphaMultiplier),
                spec.palette.atmosphere
                    .forLightSurface(theme, amount = 0.22f)
                    .copy(alpha = 0.48f * theme.atmosphereAlphaMultiplier),
                Color.Transparent,
            ),
            center = center,
            radius = radius * 1.2f,
        ),
        radius = radius * 1.2f,
        center = center,
        blendMode = theme.glowBlendMode,
    )
}

internal fun DrawScope.drawTerminator(
    spec: PlanetaSpec,
    center: Offset,
    radius: Float,
    theme: PlanetaTheme,
) {
    val lightOffset = Offset(
        x = cos(spec.lightAngleDegrees.toRadians()) * radius * 0.72f,
        y = sin(spec.lightAngleDegrees.toRadians()) * radius * 0.72f,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                spec.palette.shadow.copy(alpha = theme.terminatorMidAlpha),
                theme.terminatorColor.copy(alpha = theme.terminatorOuterAlpha),
            ),
            center = center + lightOffset,
            radius = radius * 1.34f,
        ),
        radius = radius,
        center = center,
    )
}

internal fun DrawScope.drawSpecularHighlight(
    spec: PlanetaSpec,
    center: Offset,
    radius: Float,
    theme: PlanetaTheme,
) {
    val highlightCenter = center - Offset(radius * 0.34f, radius * 0.38f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                theme.specularColor.copy(
                    alpha = if (spec.surface == PlanetaSurface.Oceanic) {
                        theme.specularOceanAlpha
                    } else {
                        theme.specularAlpha
                    },
                ),
                theme.specularColor.copy(alpha = theme.specularEdgeAlpha),
                Color.Transparent,
            ),
            center = highlightCenter,
            radius = radius * 0.58f,
        ),
        radius = radius * 0.58f,
        center = highlightCenter,
        blendMode = theme.glowBlendMode,
    )
}
