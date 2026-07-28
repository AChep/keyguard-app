package com.artemchep.keyguard.util.foundation.crypto

import com.artemchep.keyguard.util.foundation.constantTimeEquals
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoPrimitivesTest {
    private val crypto = PlatformCryptoPrimitives()

    @Test
    fun pbkdf2Sha256MatchesKnownVector() {
        val hash = crypto.pbkdf2Sha256(
            seed = "password".encodeToByteArray(),
            salt = "salt".encodeToByteArray(),
            iterations = 1,
            length = 32,
        )

        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hash.toHex(),
        )
    }

    @Test
    fun hkdfSha256WithoutSaltExpandsRawSeed() {
        val seed = ByteArray(32) { it.toByte() }

        val key = crypto.hkdfSha256(
            seed = seed,
            info = "enc".encodeToByteArray(),
            length = 32,
        )

        assertEquals(
            "9c5639fac602366b486253191cb7900d7d8e3a1514676b118d5803a11dd97213",
            key.toHex(),
        )
    }

    @Test
    fun aesEcbNoPaddingEncryptsKnownVector() {
        val encrypted = crypto.aesEcbNoPaddingEncrypt(
            key = byteArrayOf(
                0x00, 0x01, 0x02, 0x03,
                0x04, 0x05, 0x06, 0x07,
                0x08, 0x09, 0x0a, 0x0b,
                0x0c, 0x0d, 0x0e, 0x0f,
            ),
            data = byteArrayOf(
                0x00, 0x11, 0x22, 0x33,
                0x44, 0x55, 0x66, 0x77,
                0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
                0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(),
            ),
        )

        assertEquals(
            "69c4e0d86a7b0430d8cdb78070b4c55a",
            encrypted.toHex(),
        )
    }

    @Test
    fun aesCbcPkcs7RoundTrips() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { (it + 16).toByte() }
        val data = "hello".encodeToByteArray()

        val encrypted = crypto.aesCbcPkcs7Encrypt(
            key = key,
            iv = iv,
            data = data,
        )
        val decrypted = crypto.aesCbcPkcs7Decrypt(
            key = key,
            iv = iv,
            data = encrypted,
        )

        assertContentEquals(data, decrypted)
    }

    @Test
    fun hmacSha256StateMatchesOneShot() {
        val key = "secret".encodeToByteArray()
        val data = "authenticated-data".encodeToByteArray()
        val expected = crypto.hmacSha256(
            key = key,
            data = data,
        )

        val state = createHmacSha256(key)
        state.update(data, offset = 0, length = 5)
        state.update(data, offset = 5, length = data.size - 5)
        val actual = state.doFinal()

        assertContentEquals(expected, actual)
    }

    @Test
    fun constantTimeEqualsMatchesEqualContentOnly() {
        assertTrue(
            byteArrayOf(1, 2, 3).constantTimeEquals(byteArrayOf(1, 2, 3)),
        )
        assertFalse(
            byteArrayOf(1, 2, 3).constantTimeEquals(byteArrayOf(1, 2, 4)),
        )
        assertFalse(
            byteArrayOf(1, 2, 3).constantTimeEquals(byteArrayOf(1, 2, 3, 4)),
        )
        assertFalse(
            byteArrayOf(1, 2, 3, 4).constantTimeEquals(byteArrayOf(1, 2, 3)),
        )
    }
}
