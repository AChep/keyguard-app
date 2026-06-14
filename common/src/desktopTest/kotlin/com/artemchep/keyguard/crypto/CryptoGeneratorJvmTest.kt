package com.artemchep.keyguard.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CryptoGeneratorJvmTest {
    @Test
    fun `random supports singleton range at int minimum`() {
        val cryptoGenerator = CryptoGeneratorJvm()

        val value = cryptoGenerator.random(Int.MIN_VALUE..Int.MIN_VALUE)

        assertEquals(Int.MIN_VALUE, value)
    }

    @Test
    fun `random supports ranges wider than int max`() {
        val cryptoGenerator = CryptoGeneratorJvm()
        val range = Int.MIN_VALUE..0

        repeat(16) {
            val value = cryptoGenerator.random(range)

            assertTrue(value in range)
        }
    }

    @Test
    fun `random rejects empty ranges`() {
        val cryptoGenerator = CryptoGeneratorJvm()

        assertFailsWith<IllegalArgumentException> {
            cryptoGenerator.random(1 until 1)
        }
    }
}
