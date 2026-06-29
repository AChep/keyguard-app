package com.artemchep.keyguard.util.foundation.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HmacKatTest {
    private val crypto = PlatformCryptoPrimitives()

    private val key0b20 = "0b".repeat(20).hexToByteArray()
    private val key0b16 = "0b".repeat(16).hexToByteArray()
    private val keyJefe = "Jefe".encodeToByteArray()

    private val dataHiThere = "Hi There".encodeToByteArray()
    private val dataNothing = "what do ya want for nothing?".encodeToByteArray()

    @Test
    fun hmacSha256KeyBlockHiThere() {
        val result = crypto.hmac(key0b20, dataHiThere, CryptoHashAlgorithm.SHA_256)
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            result.toHex(),
        )
    }

    @Test
    fun hmacSha256KeyJefeNothing() {
        val result = crypto.hmac(keyJefe, dataNothing, CryptoHashAlgorithm.SHA_256)
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            result.toHex(),
        )
    }

    @Test
    fun hmacSha512KeyBlockHiThere() {
        val result = crypto.hmac(key0b20, dataHiThere, CryptoHashAlgorithm.SHA_512)
        assertEquals(
            "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cde" +
                "daa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854",
            result.toHex(),
        )
    }

    @Test
    fun hmacSha1KeyBlockHiThere() {
        val result = crypto.hmac(key0b20, dataHiThere, CryptoHashAlgorithm.SHA_1)
        assertEquals(
            "b617318655057264e28bc0b6fb378c8ef146be00",
            result.toHex(),
        )
    }

    @Test
    fun hmacSha1KeyJefeNothing() {
        val result = crypto.hmac(keyJefe, dataNothing, CryptoHashAlgorithm.SHA_1)
        assertEquals(
            "effcdf6ae5eb2fa2d27416d5f184df9c259a7c79",
            result.toHex(),
        )
    }

    @Test
    fun hmacMd5KeyBlockHiThere() {
        val result = crypto.hmac(key0b16, dataHiThere, CryptoHashAlgorithm.MD5)
        assertEquals(
            "9294727a3638bb1c13f48ef8158bfc9d",
            result.toHex(),
        )
    }

    @Test
    fun hmacMd5KeyJefeNothing() {
        val result = crypto.hmac(keyJefe, dataNothing, CryptoHashAlgorithm.MD5)
        assertEquals(
            "750c783e6ab0b503eaa86e310a5db738",
            result.toHex(),
        )
    }

    private fun assertIncrementalMatchesOneShot(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ) {
        val expected = crypto.hmac(key, data, algorithm)
        val state = createHmac(key, algorithm)
        val split = minOf(3, data.size)
        state.update(data, 0, split)
        state.update(data, split, data.size - split)
        val actual = state.doFinal()
        assertContentEquals(expected, actual)
    }

    @Test
    fun incrementalHmacSha256MatchesOneShot() {
        assertIncrementalMatchesOneShot(key0b20, dataHiThere, CryptoHashAlgorithm.SHA_256)
    }

    @Test
    fun incrementalHmacSha512MatchesOneShot() {
        assertIncrementalMatchesOneShot(key0b20, dataHiThere, CryptoHashAlgorithm.SHA_512)
    }

    @Test
    fun incrementalHmacSha1MatchesOneShot() {
        assertIncrementalMatchesOneShot(key0b20, dataHiThere, CryptoHashAlgorithm.SHA_1)
    }

    @Test
    fun incrementalHmacMd5MatchesOneShot() {
        assertIncrementalMatchesOneShot(key0b16, dataHiThere, CryptoHashAlgorithm.MD5)
    }

    @Test
    fun incrementalHmacSha256HelperMatchesOneShot() {
        val expected = crypto.hmacSha256(key0b20, dataHiThere)
        val actual = createHmacSha256(key0b20)
            .also { it.update(dataHiThere, 0, dataHiThere.size) }
            .doFinal()
        assertContentEquals(expected, actual)
    }

    @Test
    fun incrementalHmacUseBlockMatchesOneShot() {
        val expected = crypto.hmacSha256(key0b20, dataHiThere)
        val actual = createHmacSha256(key0b20).use { state ->
            state.update(dataHiThere, 0, dataHiThere.size)
            state.doFinal()
        }
        assertContentEquals(expected, actual)
    }

    @Test
    fun closeIsIdempotentAndSafeAfterDoFinal() {
        val state = createHmacSha256(key0b20)
        state.update(dataHiThere, 0, dataHiThere.size)
        state.doFinal()
        // doFinal() already released native resources; close() must be a safe no-op,
        // and calling it repeatedly must not double-free.
        state.close()
        state.close()
    }

    @Test
    fun closeWithoutDoFinalDoesNotThrow() {
        // Abandoning a state without finalizing it (the leak scenario) must be releasable
        // via close() without throwing or double-freeing.
        val state = createHmacSha256(key0b20)
        state.update(dataHiThere, 0, dataHiThere.size)
        state.close()
        state.close()
    }
}
