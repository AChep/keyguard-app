package com.artemchep.keyguard.util.planeta

import androidx.compose.runtime.Immutable

@Immutable
data class PlanetaOptions(
    val effects: PlanetaEffects = PlanetaEffects(),
    val planetScale: Float = 1f,
    val animationSpeed: Float = 1f,
    val theme: PlanetaTheme = PlanetaTheme.Dark,
)

enum class PlanetaTheme {
    Dark,
    Light,
}

@Immutable
data class PlanetaEffects(
    val starfield: Boolean = true,
    val atmosphere: Boolean = true,
    val bloom: Boolean = true,
    val orbitTrails: Boolean = true,
    val surfaceRelief: Boolean = true,
)
