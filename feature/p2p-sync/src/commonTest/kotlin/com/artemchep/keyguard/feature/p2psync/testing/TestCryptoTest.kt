package com.artemchep.keyguard.feature.p2psync.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestCryptoTest {
    private val crypto = TestCrypto()

    @Test
    fun `sha256 matches the FIPS 180-2 vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            crypto.sha256(ByteArray(0)).toHexString(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            crypto.sha256("abc".encodeToByteArray()).toHexString(),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            crypto.sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).toHexString(),
        )
    }

    @Test
    fun `sha256 handles messages around the block boundary`() {
        // 55, 56 and 64 bytes need one, two and two padding blocks.
        listOf(55, 56, 63, 64, 65, 1_000).forEach { size ->
            assertEquals(32, crypto.sha256(ByteArray(size) { 'a'.code.toByte() }).size)
        }
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            crypto.sha256(ByteArray(1_000_000) { 'a'.code.toByte() }).toHexString(),
        )
    }

    @Test
    fun `hmac matches the RFC 4231 vectors`() {
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            crypto.hmac(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()).toHexString(),
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            crypto.hmac("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())
                .toHexString(),
        )
    }

    @Test
    fun `hkdf matches the RFC 5869 vector`() {
        val okm = crypto.hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = ByteArray(13) { it.toByte() },
            info = ByteArray(10) { (0xf0 + it).toByte() },
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHexString(),
        )
    }

    @Test
    fun `fake signatures verify only for the signed data and key`() {
        val private = ByteArray(32) { 7 }
        val public = crypto.fakeEd25519PublicKey(private)
        val signature = crypto.ed25519Sign(private, "data".encodeToByteArray())
        assertTrue(crypto.ed25519Verify(public, "data".encodeToByteArray(), signature))
        assertFalse(crypto.ed25519Verify(public, "date".encodeToByteArray(), signature))
        val otherKey = crypto.fakeEd25519PublicKey(ByteArray(32))
        assertFalse(crypto.ed25519Verify(otherKey, "data".encodeToByteArray(), signature))
    }
}
