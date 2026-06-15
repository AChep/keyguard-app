package com.artemchep.keyguard.util.planeta

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class PlanetaOrbital(
    val orbitRadius: Float,
    val radiusFraction: Float,
    val phaseRadians: Float,
    val angularVelocity: Float,
    val tilt: Float,
    val color: Color,
    val trailAlpha: Float,
)

@Immutable
data class PlanetaRing(
    val innerRadius: Float,
    val outerRadius: Float,
    val tilt: Float,
    val rotationDegrees: Float,
    val color: Color,
    val accentColor: Color,
    val alpha: Float,
)
