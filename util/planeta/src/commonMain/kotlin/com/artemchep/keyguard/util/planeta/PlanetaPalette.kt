package com.artemchep.keyguard.util.planeta

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class PlanetaPalette(
    val shadow: Color,
    val base: Color,
    val highlight: Color,
    val accent: Color,
    val atmosphere: Color,
    val cloud: Color,
)
