package com.artemchep.keyguard.util.planeta.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.artemchep.keyguard.util.planeta.PlanetaOptions
import com.artemchep.keyguard.util.planeta.PlanetaSpec
import com.artemchep.keyguard.util.planeta.internal.TwoPi
import kotlin.math.min

internal fun DrawScope.drawPlanetaScene(
    spec: PlanetaSpec,
    options: PlanetaOptions,
    seconds: Float,
) {
    val sceneSize = min(size.width, size.height)
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = sceneSize * spec.radiusFraction * options.planetScale.coerceIn(0.2f, 1.4f)
    val rotation = ((seconds * 1_000f) / spec.spinDurationMillis) * TwoPi

    if (options.effects.starfield) {
        drawStarfield(
            seed = spec.seed,
            center = center,
            radius = radius,
            theme = options.theme,
        )
    }

    if (options.effects.orbitTrails) {
        drawOrbitTrails(
            spec = spec,
            center = center,
            radius = radius,
            theme = options.theme,
        )
    }

    drawOrbitals(
        spec = spec,
        center = center,
        radius = radius,
        seconds = seconds,
        drawFront = false,
        theme = options.theme,
    )

    if (spec.ring != null) {
        drawRing(
            ring = spec.ring,
            center = center,
            radius = radius,
            front = false,
            theme = options.theme,
        )
    }

    if (options.effects.bloom) {
        drawPlanetBloom(
            center = center,
            radius = radius,
            color = spec.palette.atmosphere,
            alpha = 1f,
            theme = options.theme,
        )
    }

    if (options.effects.atmosphere) {
        drawAtmosphere(
            center = center,
            radius = radius,
            spec = spec,
            theme = options.theme,
        )
    }

    drawPlanetBody(
        spec = spec,
        center = center,
        radius = radius,
        rotationRadians = rotation,
        effects = options.effects,
        theme = options.theme,
    )

    if (spec.ring != null) {
        drawRing(
            ring = spec.ring,
            center = center,
            radius = radius,
            front = true,
            theme = options.theme,
        )
    }

    drawOrbitals(
        spec = spec,
        center = center,
        radius = radius,
        seconds = seconds,
        drawFront = true,
        theme = options.theme,
    )
}
