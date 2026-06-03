package com.artemchep.keyguard.util.planeta.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import com.artemchep.keyguard.util.planeta.PlanetaBand
import com.artemchep.keyguard.util.planeta.PlanetaEffects
import com.artemchep.keyguard.util.planeta.PlanetaSpec
import com.artemchep.keyguard.util.planeta.PlanetaSurfaceFeature
import com.artemchep.keyguard.util.planeta.PlanetaTheme
import com.artemchep.keyguard.util.planeta.internal.TwoPi
import kotlin.math.cos
import kotlin.math.sin

internal fun DrawScope.drawPlanetBody(
    spec: PlanetaSpec,
    center: Offset,
    radius: Float,
    rotationRadians: Float,
    effects: PlanetaEffects,
    theme: PlanetaTheme,
) {
    val planetPath = Path().apply {
        addOval(Rect(center = center, radius = radius))
    }

    if (theme == PlanetaTheme.Light) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF2E4057).copy(alpha = 0.13f),
                    Color(0xFF2E4057).copy(alpha = 0.06f),
                    Color.Transparent,
                ),
                center = center + Offset(radius * 0.18f, radius * 0.28f),
                radius = radius * 1.08f,
            ),
            radius = radius * 1.08f,
            center = center + Offset(radius * 0.02f, radius * 0.04f),
        )
    }

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                spec.palette.highlight,
                spec.palette.base,
                spec.palette.shadow,
            ),
            center = center - Offset(radius * 0.34f, radius * 0.42f),
            radius = radius * 1.42f,
        ),
        radius = radius,
        center = center,
    )

    clipPath(planetPath) {
        rotate(spec.axialTiltDegrees, center) {
            spec.bands.forEach { band ->
                drawBand(
                    band = band,
                    center = center,
                    radius = radius,
                    rotationRadians = rotationRadians,
                )
            }

            spec.features.forEach { feature ->
                drawSurfaceFeature(
                    feature = feature,
                    center = center,
                    radius = radius,
                    rotationRadians = rotationRadians,
                    relief = effects.surfaceRelief,
                    theme = theme,
                )
            }

            spec.clouds.forEach { cloud ->
                drawSurfaceFeature(
                    feature = cloud,
                    center = center,
                    radius = radius,
                    rotationRadians = rotationRadians * 1.22f + 0.7f,
                    relief = false,
                    theme = theme,
                )
            }
        }

        drawTerminator(
            spec = spec,
            center = center,
            radius = radius,
            theme = theme,
        )
        drawSpecularHighlight(
            spec = spec,
            center = center,
            radius = radius,
            theme = theme,
        )
    }

    drawCircle(
        color = theme.rimColor,
        radius = radius,
        center = center,
        style = Stroke(width = radius * 0.012f),
        blendMode = theme.glowBlendMode,
    )
}

private fun DrawScope.drawBand(
    band: PlanetaBand,
    center: Offset,
    radius: Float,
    rotationRadians: Float,
) {
    val path = Path()
    val width = radius * 2.4f
    val left = center.x - width / 2f
    val samples = 18
    val y = center.y + band.latitude * radius
    val halfHeight = band.height * radius

    fun wave(sample: Int): Float {
        val t = sample / samples.toFloat()
        return sin(t * TwoPi * band.waveFrequency + band.phaseRadians + rotationRadians) *
            band.waveAmplitude *
            radius
    }

    path.moveTo(left, y - halfHeight + wave(0))
    for (sample in 1..samples) {
        val x = left + width * sample / samples
        path.lineTo(x, y - halfHeight + wave(sample))
    }
    for (sample in samples downTo 0) {
        val x = left + width * sample / samples
        path.lineTo(x, y + halfHeight + wave(sample + 4))
    }
    path.close()

    drawPath(
        path = path,
        color = band.color.copy(alpha = band.alpha),
    )
}

private fun DrawScope.drawSurfaceFeature(
    feature: PlanetaSurfaceFeature,
    center: Offset,
    radius: Float,
    rotationRadians: Float,
    relief: Boolean,
    theme: PlanetaTheme,
) {
    val longitude = feature.longitudeRadians + rotationRadians
    val latitude = feature.latitudeRadians
    val cosLat = cos(latitude)
    val x = sin(longitude) * cosLat
    val y = sin(latitude)
    val z = cos(longitude) * cosLat
    if (z < -0.14f) return

    val visible = ((z + 0.14f) / 1.14f).coerceIn(0f, 1f)
    val projectedCenter = center + Offset(x * radius, y * radius * 0.96f)
    val featureRadius = radius * feature.radiusFraction * (0.58f + 0.42f * visible)
    val featureSize = Size(
        width = featureRadius * feature.stretch,
        height = featureRadius,
    )
    val alpha = feature.alpha * visible

    if (relief && featureRadius > 1f) {
        drawOval(
            color = theme.featureShadowColor.copy(alpha = alpha * theme.featureShadowAlphaMultiplier),
            topLeft = projectedCenter - Offset(featureSize.width * 0.45f, featureSize.height * 0.25f),
            size = featureSize,
        )
    }

    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(
                feature.color.copy(alpha = alpha),
                feature.color.copy(alpha = alpha * 0.74f),
                feature.color.copy(alpha = 0f),
            ),
            center = projectedCenter - Offset(featureRadius * 0.2f, featureRadius * 0.18f),
            radius = featureSize.maxDimension,
        ),
        topLeft = projectedCenter - Offset(featureSize.width / 2f, featureSize.height / 2f),
        size = featureSize,
        blendMode = if (relief || theme == PlanetaTheme.Light) BlendMode.SrcOver else BlendMode.Plus,
    )
}
