package com.artemchep.keyguard.util.planeta

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.util.planeta.generation.PlanetaSpecGenerator

@Immutable
data class PlanetaSpec(
    val seed: Long,
    val surface: PlanetaSurface,
    val radiusFraction: Float,
    val spinDurationMillis: Int,
    val axialTiltDegrees: Float,
    val lightAngleDegrees: Float,
    val palette: PlanetaPalette,
    val bands: List<PlanetaBand>,
    val features: List<PlanetaSurfaceFeature>,
    val clouds: List<PlanetaSurfaceFeature>,
    val orbitals: List<PlanetaOrbital>,
    val ring: PlanetaRing?,
) {
    companion object {
        fun fromFingerprint(fingerprint: String): PlanetaSpec =
            PlanetaSpecGenerator.generate(fingerprint)
    }
}
