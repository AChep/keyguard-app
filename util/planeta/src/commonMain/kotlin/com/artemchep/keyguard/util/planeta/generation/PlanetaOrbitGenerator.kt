package com.artemchep.keyguard.util.planeta.generation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.artemchep.keyguard.util.planeta.PlanetaOrbital
import com.artemchep.keyguard.util.planeta.PlanetaPalette
import com.artemchep.keyguard.util.planeta.PlanetaRing
import com.artemchep.keyguard.util.planeta.PlanetaSurface
import com.artemchep.keyguard.util.planeta.internal.PlanetaRandom

internal fun generateOrbitals(
    surface: PlanetaSurface,
    palette: PlanetaPalette,
    random: PlanetaRandom,
): List<PlanetaOrbital> {
    val max = when (surface) {
        PlanetaSurface.GasGiant -> 4
        PlanetaSurface.Terrestrial,
        PlanetaSurface.Oceanic -> 3
        else -> 2
    }
    val count = random.nextInt(0, max + 1)

    return List(count) { index ->
        PlanetaOrbital(
            orbitRadius = 1.28f + index * random.nextFloat(0.24f, 0.42f) + random.nextFloat(0f, 0.22f),
            radiusFraction = random.nextFloat(0.035f, 0.085f),
            phaseRadians = random.nextRadians(),
            angularVelocity = random.nextFloat(0.18f, 0.56f) * if (random.nextBoolean()) 1f else -1f,
            tilt = random.nextFloat(0.34f, 0.58f),
            color = lerp(palette.highlight, Color.White, random.nextFloat(0.1f, 0.45f)),
            trailAlpha = random.nextFloat(0.1f, 0.24f),
        )
    }
}

internal fun generateRing(
    surface: PlanetaSurface,
    palette: PlanetaPalette,
    random: PlanetaRandom,
): PlanetaRing? {
    val probability = when (surface) {
        PlanetaSurface.GasGiant -> 0.78f
        PlanetaSurface.Ice -> 0.48f
        else -> 0.28f
    }
    if (random.nextFloat() > probability) return null

    val inner = random.nextFloat(1.18f, 1.36f)
    return PlanetaRing(
        innerRadius = inner,
        outerRadius = inner + random.nextFloat(0.18f, 0.34f),
        tilt = random.nextFloat(0.22f, 0.36f),
        rotationDegrees = random.nextFloat(-18f, 18f),
        color = lerp(palette.highlight, Color.White, random.nextFloat(0.05f, 0.26f)),
        accentColor = palette.accent,
        alpha = random.nextFloat(0.34f, 0.62f),
    )
}
