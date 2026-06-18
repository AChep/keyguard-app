package com.artemchep.keyguard.util.planeta

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class PlanetaBand(
    val latitude: Float,
    val height: Float,
    val color: Color,
    val alpha: Float,
    val waveAmplitude: Float,
    val waveFrequency: Float,
    val phaseRadians: Float,
)

@Immutable
data class PlanetaSurfaceFeature(
    val longitudeRadians: Float,
    val latitudeRadians: Float,
    val radiusFraction: Float,
    val stretch: Float,
    val color: Color,
    val alpha: Float,
)
