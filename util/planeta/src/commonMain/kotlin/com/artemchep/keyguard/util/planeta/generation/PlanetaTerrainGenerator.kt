package com.artemchep.keyguard.util.planeta.generation

import com.artemchep.keyguard.util.planeta.PlanetaBand
import com.artemchep.keyguard.util.planeta.PlanetaPalette
import com.artemchep.keyguard.util.planeta.PlanetaSurface
import com.artemchep.keyguard.util.planeta.PlanetaSurfaceFeature
import com.artemchep.keyguard.util.planeta.internal.PlanetaRandom

internal fun generateBands(
    surface: PlanetaSurface,
    palette: PlanetaPalette,
    random: PlanetaRandom,
): List<PlanetaBand> {
    val count = when (surface) {
        PlanetaSurface.GasGiant -> random.nextInt(9, 15)
        PlanetaSurface.Oceanic -> random.nextInt(3, 6)
        else -> random.nextInt(4, 8)
    }

    return List(count) { index ->
        val lane = (index + 0.5f) / count
        val latitude = lane * 2f - 1f + random.nextFloat(-0.08f, 0.08f)
        val color = when {
            surface == PlanetaSurface.GasGiant && index % 3 == 0 -> palette.highlight
            surface == PlanetaSurface.GasGiant && index % 2 == 0 -> palette.accent
            index % 2 == 0 -> palette.base.shift(random.nextFloat(-0.12f, 0.12f))
            else -> palette.highlight.shift(random.nextFloat(-0.16f, 0.04f))
        }

        PlanetaBand(
            latitude = latitude.coerceIn(-0.96f, 0.96f),
            height = random.nextFloat(0.06f, if (surface == PlanetaSurface.GasGiant) 0.17f else 0.11f),
            color = color,
            alpha = random.nextFloat(0.16f, if (surface == PlanetaSurface.GasGiant) 0.55f else 0.28f),
            waveAmplitude = random.nextFloat(0.01f, if (surface == PlanetaSurface.GasGiant) 0.055f else 0.032f),
            waveFrequency = random.nextFloat(1.2f, 3.8f),
            phaseRadians = random.nextRadians(),
        )
    }
}

internal fun generateFeatures(
    surface: PlanetaSurface,
    palette: PlanetaPalette,
    random: PlanetaRandom,
): List<PlanetaSurfaceFeature> {
    val count = when (surface) {
        PlanetaSurface.GasGiant -> random.nextInt(5, 10)
        PlanetaSurface.Volcanic -> random.nextInt(24, 38)
        else -> random.nextInt(18, 32)
    }

    return List(count) { index ->
        val accentBias = index % 4 == 0 ||
            (surface == PlanetaSurface.Volcanic && index % 3 == 0)
        val color = when {
            accentBias -> palette.accent
            index % 3 == 0 -> palette.highlight.shift(-0.16f)
            else -> palette.base.shift(random.nextFloat(-0.18f, 0.14f))
        }

        PlanetaSurfaceFeature(
            longitudeRadians = random.nextRadians(),
            latitudeRadians = random.nextFloat(-1.05f, 1.05f),
            radiusFraction = random.nextFloat(
                if (surface == PlanetaSurface.GasGiant) 0.045f else 0.025f,
                if (surface == PlanetaSurface.GasGiant) 0.16f else 0.115f,
            ),
            stretch = random.nextFloat(0.65f, 1.85f),
            color = color,
            alpha = random.nextFloat(
                if (surface == PlanetaSurface.GasGiant) 0.18f else 0.34f,
                if (surface == PlanetaSurface.GasGiant) 0.46f else 0.72f,
            ),
        )
    }
}

internal fun generateClouds(
    surface: PlanetaSurface,
    palette: PlanetaPalette,
    random: PlanetaRandom,
): List<PlanetaSurfaceFeature> {
    val probability = when (surface) {
        PlanetaSurface.Volcanic -> 0.35f
        PlanetaSurface.Ice -> 0.55f
        else -> 0.78f
    }
    if (random.nextFloat() > probability) return emptyList()

    return List(random.nextInt(7, 15)) {
        PlanetaSurfaceFeature(
            longitudeRadians = random.nextRadians(),
            latitudeRadians = random.nextFloat(-0.85f, 0.85f),
            radiusFraction = random.nextFloat(0.035f, 0.12f),
            stretch = random.nextFloat(1.4f, 3.4f),
            color = palette.cloud,
            alpha = random.nextFloat(0.12f, 0.34f),
        )
    }
}
