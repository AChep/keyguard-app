package com.artemchep.keyguard.util.planeta.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.artemchep.keyguard.util.planeta.PlanetaRing
import com.artemchep.keyguard.util.planeta.PlanetaTheme

internal fun DrawScope.drawRing(
    ring: PlanetaRing,
    center: Offset,
    radius: Float,
    front: Boolean,
    theme: PlanetaTheme,
) {
    val outerX = radius * ring.outerRadius
    val outerY = radius * ring.outerRadius * ring.tilt
    val width = radius * (ring.outerRadius - ring.innerRadius)
    val drawRingStroke: DrawScope.() -> Unit = {
        if (theme == PlanetaTheme.Light) {
            drawOval(
                color = Color(0xFF26384C).copy(alpha = 0.08f),
                topLeft = Offset(center.x - outerX, center.y - outerY),
                size = Size(outerX * 2f, outerY * 2f),
                style = Stroke(
                    width = width + radius * 0.018f,
                    cap = StrokeCap.Round,
                ),
            )
        }

        drawOval(
            brush = Brush.linearGradient(
                colors = listOf(
                    ring.color.forLightSurface(theme, amount = 0.22f).copy(alpha = 0.1f * ring.alpha * theme.ringAlphaMultiplier),
                    ring.accentColor.forLightSurface(theme, amount = 0.2f).copy(alpha = 0.58f * ring.alpha * theme.ringAlphaMultiplier),
                    ring.color.forLightSurface(theme, amount = 0.16f).copy(alpha = 0.82f * ring.alpha * theme.ringAlphaMultiplier),
                    ring.color.forLightSurface(theme, amount = 0.2f).copy(alpha = 0.18f * ring.alpha * theme.ringAlphaMultiplier),
                ),
                start = Offset(center.x - outerX, center.y),
                end = Offset(center.x + outerX, center.y),
            ),
            topLeft = Offset(center.x - outerX, center.y - outerY),
            size = Size(outerX * 2f, outerY * 2f),
            style = Stroke(
                width = width,
                cap = StrokeCap.Round,
            ),
        )
    }

    withTransform({
        rotate(ring.rotationDegrees, center)
    }) {
        if (front) {
            clipRect(
                left = center.x - outerX - width,
                top = center.y - width * 0.2f,
                right = center.x + outerX + width,
                bottom = center.y + outerY + width,
            ) {
                drawRingStroke()
            }
        } else {
            drawRingStroke()
        }
    }
}
