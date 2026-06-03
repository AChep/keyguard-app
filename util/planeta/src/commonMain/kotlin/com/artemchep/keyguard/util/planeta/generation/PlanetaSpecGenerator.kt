package com.artemchep.keyguard.util.planeta.generation

import com.artemchep.keyguard.util.planeta.PlanetaSpec
import com.artemchep.keyguard.util.planeta.PlanetaSurface
import com.artemchep.keyguard.util.planeta.internal.PlanetaRandom
import com.artemchep.keyguard.util.planeta.internal.fingerprintSeed

internal object PlanetaSpecGenerator {
    fun generate(fingerprint: String): PlanetaSpec {
        val seed = fingerprintSeed(fingerprint)
        val random = PlanetaRandom(seed)
        val surface = PlanetaSurface.entries[random.nextInt(PlanetaSurface.entries.size)]
        val palette = paletteFor(surface, random)
        val radiusFraction = random.nextFloat(0.31f, 0.39f)
        val spinDurationMillis = random.nextInt(16_000, 48_000)
        val axialTiltDegrees = random.nextFloat(-24f, 24f)
        val lightAngleDegrees = random.nextFloat(-42f, -18f)
        val bands = generateBands(surface, palette, random)
        val features = generateFeatures(surface, palette, random)
        val clouds = generateClouds(surface, palette, random)
        val orbitals = generateOrbitals(surface, palette, random)
        val ring = generateRing(surface, palette, random)

        return PlanetaSpec(
            seed = seed,
            surface = surface,
            radiusFraction = radiusFraction,
            spinDurationMillis = spinDurationMillis,
            axialTiltDegrees = axialTiltDegrees,
            lightAngleDegrees = lightAngleDegrees,
            palette = palette,
            bands = bands,
            features = features,
            clouds = clouds,
            orbitals = orbitals,
            ring = ring,
        )
    }
}
