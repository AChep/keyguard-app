package com.artemchep.keyguard.util.planeta

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import com.artemchep.keyguard.util.planeta.render.drawPlanetBloom
import com.artemchep.keyguard.util.planeta.render.drawStarfield
import kotlin.math.min

@Stable
fun Modifier.planetaBackdrop(
    fingerprint: String,
    effects: PlanetaEffects = PlanetaEffects(),
    theme: PlanetaTheme = PlanetaTheme.Dark,
): Modifier = planetaBackdrop(
    spec = PlanetaSpec.fromFingerprint(fingerprint),
    effects = effects,
    theme = theme,
)

@Stable
fun Modifier.planetaBackdrop(
    spec: PlanetaSpec,
    effects: PlanetaEffects = PlanetaEffects(),
    theme: PlanetaTheme = PlanetaTheme.Dark,
): Modifier = this.then(
    PlanetaBackdropModifier(
        spec = spec,
        effects = effects,
        theme = theme,
    ),
)

private data class PlanetaBackdropModifier(
    private val spec: PlanetaSpec,
    private val effects: PlanetaEffects,
    private val theme: PlanetaTheme,
) : androidx.compose.ui.draw.DrawModifier {
    override fun ContentDrawScope.draw() {
        val radius = min(size.width, size.height) * spec.radiusFraction
        val center = Offset(size.width / 2f, size.height / 2f)

        if (effects.starfield) {
            drawStarfield(
                seed = spec.seed,
                center = center,
                radius = radius,
                theme = theme,
            )
        }
        if (effects.bloom) {
            drawPlanetBloom(
                center = center,
                radius = radius,
                color = spec.palette.atmosphere,
                alpha = 0.7f,
                theme = theme,
            )
        }

        drawContent()
    }
}
