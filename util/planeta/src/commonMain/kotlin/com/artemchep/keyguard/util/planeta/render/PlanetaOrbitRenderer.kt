package com.artemchep.keyguard.util.planeta.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.artemchep.keyguard.util.planeta.PlanetaSpec
import com.artemchep.keyguard.util.planeta.PlanetaTheme
import com.artemchep.keyguard.util.planeta.internal.rotateDegrees
import kotlin.math.cos
import kotlin.math.sin

internal fun DrawScope.drawOrbitTrails(
    spec: PlanetaSpec,
    center: Offset,
    radius: Float,
    theme: PlanetaTheme,
) {
    spec.orbitals.forEach { orbital ->
        withTransform({
            rotate(spec.axialTiltDegrees * 0.3f, center)
        }) {
            drawOval(
                color = orbital.color
                    .forLightSurface(theme, amount = 0.4f)
                    .copy(alpha = orbital.trailAlpha * theme.orbitTrailAlphaMultiplier),
                topLeft = Offset(
                    x = center.x - radius * orbital.orbitRadius,
                    y = center.y - radius * orbital.orbitRadius * orbital.tilt,
                ),
                size = Size(
                    width = radius * orbital.orbitRadius * 2f,
                    height = radius * orbital.orbitRadius * orbital.tilt * 2f,
                ),
                style = Stroke(width = radius * 0.006f),
            )
        }
    }
}

internal fun DrawScope.drawOrbitals(
    spec: PlanetaSpec,
    center: Offset,
    radius: Float,
    seconds: Float,
    drawFront: Boolean,
    theme: PlanetaTheme,
) {
    spec.orbitals.forEach { orbital ->
        val angle = orbital.phaseRadians + seconds * orbital.angularVelocity
        val depth = sin(angle)
        if ((depth >= 0f) != drawFront) return@forEach

        val x = cos(angle) * orbital.orbitRadius * radius
        val y = depth * orbital.orbitRadius * radius * orbital.tilt
        val rotated = Offset(x, y).rotateDegrees(spec.axialTiltDegrees * 0.3f)
        val orbitalCenter = center + rotated
        val orbitalRadius = radius * orbital.radiusFraction * (0.78f + 0.22f * (depth + 1f) / 2f)

        if (theme == PlanetaTheme.Light) {
            drawCircle(
                color = Color(0xFF233247).copy(alpha = 0.12f),
                radius = orbitalRadius * 1.12f,
                center = orbitalCenter + Offset(orbitalRadius * 0.12f, orbitalRadius * 0.18f),
            )
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    orbital.highlightColor(theme),
                    orbital.color,
                    orbital.color
                        .forLightSurface(theme, amount = 0.24f)
                        .copy(alpha = if (theme == PlanetaTheme.Light) 0.54f else 0.38f),
                ),
                center = orbitalCenter - Offset(orbitalRadius * 0.28f, orbitalRadius * 0.32f),
                radius = orbitalRadius * 1.2f,
            ),
            radius = orbitalRadius,
            center = orbitalCenter,
        )
    }
}
