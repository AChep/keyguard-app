package com.artemchep.keyguard.feature.p2psync.enroll

import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SasDerivationTest {
    private val crypto = TestCrypto()

    private val scannerNonce = ByteArray(32) { 0x11 }
    private val displayerNonce = ByteArray(32) { 0x22 }

    private fun sas(psk: ByteArray, hash: ByteArray, a: ByteArray, b: ByteArray) =
        SasDerivation.sas(psk, hash, a, b, scannerNonce, displayerNonce, crypto, SyncScope.APP)

    // Computed independently with Python's hmac and hashlib (RFC 5869 HKDF), info
    // `keyguard-sas/app/v1` followed by the sorted statics and the nonces.
    @Test
    fun `fixed vectors`() {
        val counting = ByteArray(32) { it.toByte() }
        val hash = ByteArray(32) { 0xAA.toByte() }
        val one = ByteArray(32) { 1 }
        val two = ByteArray(32) { 2 }
        assertEquals("726116", sas(counting, hash, one, two))
        // The first byte sorts the statics as unsigned: 0x0F… before 0xF0….
        val high = ByteArray(32).also { it[0] = 0xF0.toByte() }
        val low = ByteArray(32) { 0xFF.toByte() }.also { it[0] = 0x0F }
        val psk = ByteArray(32) { 7 }
        val otherHash = ByteArray(32) { (32 + it).toByte() }
        assertEquals("720730", sas(psk, otherHash, high, low))
        assertEquals("720730", sas(psk, otherHash, low, high))
        // The nonces keep their roles.
        assertEquals(
            "386387",
            SasDerivation.sas(psk, otherHash, high, low, displayerNonce, scannerNonce, crypto, SyncScope.APP),
        )
        val same = ByteArray(32) { 5 }
        val zero = ByteArray(32)
        assertEquals("607766", SasDerivation.sas(zero, zero, same, same, zero, zero, crypto, SyncScope.APP))
    }

    @Test
    fun `the commitment is the domain separated SHA-256 of the nonce`() {
        assertEquals(
            "0fdf7a5e40a12befb8614d62e7db7039e0c5f632b23531492d8d67a56e583860",
            P2pEncoding.hex(SasDerivation.commit(displayerNonce, crypto)),
        )
    }

    @Test
    fun `both devices get the same digits whatever the order of the statics`() {
        val random = Random(9)
        repeat(50) {
            val psk = random.nextBytes(32)
            val hash = random.nextBytes(32)
            val a = random.nextBytes(32)
            val b = random.nextBytes(32)
            val sas = sas(psk, hash, a, b)
            assertEquals(sas, sas(psk, hash, b, a))
            assertEquals(SasDerivation.DIGITS, sas.length)
            assertTrue(sas.all { it.isDigit() })
        }
    }

    @Test
    fun `a different handshake or nonce gives different digits`() {
        val psk = ByteArray(32) { 1 }
        val a = ByteArray(32) { 2 }
        val b = ByteArray(32) { 3 }
        val hash = ByteArray(32) { 4 }
        val digits = sas(psk, hash, a, b)
        assertNotEquals(digits, sas(psk, ByteArray(32) { 5 }, a, b))
        assertNotEquals(
            digits,
            SasDerivation.sas(psk, hash, a, b, ByteArray(32) { 6 }, displayerNonce, crypto, SyncScope.APP),
        )
        assertNotEquals(
            digits,
            SasDerivation.sas(psk, hash, a, b, scannerNonce, ByteArray(32) { 6 }, crypto, SyncScope.APP),
        )
    }
}
