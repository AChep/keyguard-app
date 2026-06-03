package com.artemchep.keyguard.util.planeta.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.artemchep.keyguard.util.planeta.PlanetaTheme
import com.artemchep.keyguard.util.planeta.internal.PlanetaRandom

internal fun DrawScope.drawStarfield(
    seed: Long,
    center: Offset,
    radius: Float,
    theme: PlanetaTheme,
) {
    val random = PlanetaRandom(seed xor -3382325739171139617L)
    repeat(72) { index ->
        val x = random.nextFloat(0f, size.width)
        val y = random.nextFloat(0f, size.height)
        val distanceFromPlanet = (Offset(x, y) - center).getDistance()
        if (distanceFromPlanet > radius * 0.88f || index % 7 == 0) {
            val starRadius = random.nextFloat(
                min = 0.35f,
                max = if (theme == PlanetaTheme.Light) 1.65f else 1.45f,
            )
            drawCircle(
                color = theme.starColor.copy(alpha = random.nextFloat(theme.starAlphaMin, theme.starAlphaMax)),
                radius = starRadius,
                center = Offset(x, y),
            )
        }
    }
}
