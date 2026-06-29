package com.artemchep.keyguard.util.foundation.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HkdfKatTest {
    private val crypto = PlatformCryptoPrimitives()

    @Test
    fun rfc5869_tc1() {
        val ikm = "0b".repeat(22).hexToByteArray()
        val salt = "000102030405060708090a0b0c".hexToByteArray()
        val info = "f0f1f2f3f4f5f6f7f8f9".hexToByteArray()
        val expected =
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"
        val result = crypto.hkdfSha256(ikm, salt, info, 42)
        assertEquals(expected, result.toHex())
    }

    @Test
    fun rfc5869_tc2() {
        val ikm = ByteArray(80) { it.toByte() }
        val salt = ByteArray(80) { (0x60 + it).toByte() }
        val info = ByteArray(80) { (0xb0 + it).toByte() }
        val expected =
            "b11e398dc80327a1c8e7f78c596a4934" +
                "4f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09" +
                "da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f" +
                "1d87"
        val result = crypto.hkdfSha256(ikm, salt, info, 82)
        assertEquals(expected, result.toHex())
    }

    @Test
    fun rfc5869_tc3() {
        val ikm = "0b".repeat(22).hexToByteArray()
        val salt = ByteArray(0)
        val info = ByteArray(0)
        val expected =
            "8da4e775a563c18f715f802a063c5a31" +
                "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8"
        val result = crypto.hkdfSha256(ikm, salt, info, 42)
        assertEquals(expected, result.toHex())
    }

    @Test
    fun nullSalt_skipExtract_repoAnchor() {
        val seed = ByteArray(32) { it.toByte() }
        val info = "enc".encodeToByteArray()
        val expected = "9c5639fac602366b486253191cb7900d7d8e3a1514676b118d5803a11dd97213"
        val result = crypto.hkdfSha256(seed, null, info, 32)
        assertEquals(expected, result.toHex())
    }

    @Test
    fun nullSalt_vs_emptySalt_differ() {
        val seed = ByteArray(32) { it.toByte() }
        val info = "enc".encodeToByteArray()
        val nullSalt = crypto.hkdfSha256(seed, null, info, 32)
        val emptySalt = crypto.hkdfSha256(seed, ByteArray(0), info, 32)
        assertFalse(nullSalt.contentEquals(emptySalt))
    }

    @Test
    fun emptySeed_nullSalt_doesNotThrow() {
        val result = crypto.hkdfSha256(ByteArray(0), null, ByteArray(0), 32)
        assertEquals(32, result.size)
    }
}
