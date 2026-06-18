package com.artemchep.keyguard.util.planeta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlanetaSpecTest {
    @Test
    fun optionsDefaultToDarkTheme() {
        assertEquals(PlanetaTheme.Dark, PlanetaOptions().theme)
    }

    @Test
    fun optionsCanSelectLightTheme() {
        val options = PlanetaOptions(theme = PlanetaTheme.Light)

        assertEquals(PlanetaTheme.Light, options.theme)
    }

    @Test
    fun sameFingerprintProducesSameSpec() {
        val first = PlanetaSpec.fromFingerprint("vault:item:01:5fe934")
        val second = PlanetaSpec.fromFingerprint("vault:item:01:5fe934")

        assertEquals(first, second)
    }

    @Test
    fun differentFingerprintsChangeGeneratedPlanet() {
        val first = PlanetaSpec.fromFingerprint("vault:item:01:5fe934")
        val second = PlanetaSpec.fromFingerprint("vault:item:02:5fe934")

        assertNotEquals(first.seed, second.seed)
        assertNotEquals(first, second)
    }

    @Test
    fun generatedValuesStayInsideRenderableRanges() {
        repeat(128) { index ->
            val spec = PlanetaSpec.fromFingerprint("planet-$index")

            assertTrue(spec.radiusFraction in 0.31f..0.39f)
            assertTrue(spec.spinDurationMillis in 16_000..48_000)
            assertTrue(spec.axialTiltDegrees in -24f..24f)
            assertTrue(spec.lightAngleDegrees in -42f..-18f)
            assertTrue(spec.bands.all { it.latitude in -0.96f..0.96f })
            assertTrue(spec.features.all { it.radiusFraction in 0.025f..0.16f })
            assertTrue(spec.clouds.all { it.radiusFraction in 0.035f..0.12f })
            assertTrue(
                spec.orbitals.all {
                    it.orbitRadius >= 1.28f && it.radiusFraction in 0.035f..0.085f
                },
            )
            spec.ring?.let { ring ->
                assertTrue(ring.innerRadius in 1.18f..1.36f)
                assertTrue(ring.outerRadius > ring.innerRadius)
                assertTrue(ring.tilt in 0.22f..0.36f)
            }
        }
    }
}
