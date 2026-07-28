package com.artemchep.keyguard.util.foundation.crypto

import com.artemchep.keyguard.util.foundation.constantTimeEquals
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RandomConstantTimeTest {
    private val crypto = PlatformCryptoPrimitives()

    @Test
    fun randomBytes_lengthCorrect() {
        assertEquals(33, crypto.randomBytes(33).size)
        assertTrue(crypto.randomBytes(0).isEmpty())
    }

    @Test
    fun randomBytes_twoDrawsDiffer() {
        assertFalse(crypto.randomBytes(32).contentEquals(crypto.randomBytes(32)))
    }

    @Test
    fun randomInt_until_inRange() {
        repeat(10000) {
            val r = crypto.randomInt(1000)
            assertTrue(r in 0 until 1000)
        }
    }

    @Test
    fun randomInt_until_moduloBiasIsAbsent() {
        // 1610612736 = 3 * 2^29
        val until = 0x60000000
        // 1073741824 = 2^32 mod until ; residues [0, this) get an EXTRA hit under modulo bias
        val lowResidueBound = 0x40000000
        val n = 200000
        var inLow = 0
        repeat(n) {
            if (crypto.randomInt(until) < lowResidueBound) inLow++
        }
        val fraction = inLow.toDouble() / n
        // Uniform generator => ~0.667 ; modulo-biased generator => ~0.75. Assert clearly on the uniform side.
        assertTrue(
            fraction < 0.71,
            "randomInt(until) shows modulo bias: P(low residues)=$fraction (uniform~0.667, biased~0.75)",
        )
    }

    @Test
    fun constantTimeEquals_equalContent() {
        assertTrue(byteArrayOf(1, 2, 3).constantTimeEquals(byteArrayOf(1, 2, 3)))
        assertTrue(ByteArray(0).constantTimeEquals(ByteArray(0)))
    }

    @Test
    fun constantTimeEquals_differingByte() {
        assertFalse(byteArrayOf(1, 2, 3, 4).constantTimeEquals(byteArrayOf(9, 2, 3, 4)))
        assertFalse(byteArrayOf(1, 2, 3, 4).constantTimeEquals(byteArrayOf(1, 2, 9, 4)))
        assertFalse(byteArrayOf(1, 2, 3, 4).constantTimeEquals(byteArrayOf(1, 2, 3, 9)))
    }

    @Test
    fun constantTimeEquals_lengthMismatch() {
        assertFalse(byteArrayOf(1, 2, 3).constantTimeEquals(byteArrayOf(1, 2, 3, 4)))
        assertFalse(byteArrayOf(1, 2, 3, 4).constantTimeEquals(byteArrayOf(1, 2, 3)))
        assertFalse(byteArrayOf(1, 2, 3).constantTimeEquals(ByteArray(0)))
        assertFalse(ByteArray(0).constantTimeEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun constantTimeEquals_signEdgeBytes() {
        val a = byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte())
        assertTrue(a.constantTimeEquals(byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte())))
        assertFalse(a.constantTimeEquals(byteArrayOf(0x00, 0xFF.toByte(), 0x7F)))
    }

    @Test
    fun constantTimeEquals_sameReference() {
        val a = byteArrayOf(9, 9, 9)
        assertTrue(a.constantTimeEquals(a))
    }

    @Test
    fun constantTimeEquals_property_matchesContentEquals() {
        val random = Random(seed = 1234)
        repeat(200) {
            val length = random.nextInt(0, 41)
            val a = random.nextBytes(length)
            val b: ByteArray =
                when {
                    random.nextBoolean() -> a.copyOf()
                    length == 0 -> random.nextBytes(random.nextInt(1, 41))
                    else -> {
                        val mutated = a.copyOf()
                        val index = random.nextInt(0, length)
                        mutated[index] = (mutated[index] + 1).toByte()
                        mutated
                    }
                }
            assertEquals(a.contentEquals(b), a.constantTimeEquals(b))
        }
    }
}
