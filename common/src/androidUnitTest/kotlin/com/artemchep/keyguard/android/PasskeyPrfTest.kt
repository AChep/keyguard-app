package com.artemchep.keyguard.android

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PasskeyPrfTest {
    private val cryptoGenerator = JvmCryptoGenerator()

    @Test
    fun `computeWebAuthnPrf returns 32 bytes`() {
        val output = computeWebAuthnPrf(
            cryptoGenerator,
            prfSecretBytes = "prf-secret".toByteArray(),
            prfInput = "prf-input".toByteArray(),
        )
        org.junit.Assert.assertEquals(32, output.size)
    }

    @Test
    fun `computeWebAuthnPrf is deterministic for the same inputs`() {
        val prfSecretBytes = "prf-secret".toByteArray()
        val prfInput = "prf-input".toByteArray()
        val output1 = computeWebAuthnPrf(cryptoGenerator, prfSecretBytes, prfInput)
        val output2 = computeWebAuthnPrf(cryptoGenerator, prfSecretBytes, prfInput)
        assertArrayEquals(output1, output2)
    }

    @Test
    fun `computeWebAuthnPrf different inputs produce different outputs`() {
        val prfSecretBytes = "prf-secret".toByteArray()
        val output1 = computeWebAuthnPrf(cryptoGenerator, prfSecretBytes, "input-a".toByteArray())
        val output2 = computeWebAuthnPrf(cryptoGenerator, prfSecretBytes, "input-b".toByteArray())
        assertFalse(output1.contentEquals(output2))
    }

    @Test
    fun `computeWebAuthnPrf different secrets produce different outputs`() {
        val prfInput = "prf-input".toByteArray()
        val output1 = computeWebAuthnPrf(cryptoGenerator, "secret-1".toByteArray(), prfInput)
        val output2 = computeWebAuthnPrf(cryptoGenerator, "secret-2".toByteArray(), prfInput)
        assertFalse(output1.contentEquals(output2))
    }

    /**
     * Verifies the algorithm is:
     *   prfSalt   = SHA-256("WebAuthn PRF\x00" || prfInput)
     *   prfOutput = HMAC-SHA-256(prfSecretBytes, prfSalt)
     *
     * The expected value is computed independently using raw JVM crypto so that
     * the test does not simply re-execute the same code path it is testing.
     */
    @Test
    fun `computeWebAuthnPrf matches W3C spec algorithm`() {
        val prfSecretBytes = "test-prf-secret".toByteArray(Charsets.UTF_8)
        val prfInput = "test-prf-input".toByteArray(Charsets.UTF_8)

        val prfSaltInput = PasskeyUtils.PRF_LABEL + prfInput
        val prfSalt = MessageDigest.getInstance("SHA-256").digest(prfSaltInput)
        val expected = hmacSha256(prfSecretBytes, prfSalt)

        val actual = computeWebAuthnPrf(cryptoGenerator, prfSecretBytes, prfInput)
        assertArrayEquals(expected, actual)
    }

    /**
     * Verifies output does NOT equal the old (incorrect) two-step HMAC derivation that
     * used the signing private key as key material, ensuring we produce a distinct value.
     */
    @Test
    fun `computeWebAuthnPrf does not match old private-key-derived computation`() {
        val signingKeyBytes = "signing-private-key".toByteArray(Charsets.UTF_8)
        val prfSecretBytes = "separate-prf-secret".toByteArray(Charsets.UTF_8)
        val prfInput = "test-input".toByteArray(Charsets.UTF_8)

        val prfSalt = MessageDigest.getInstance("SHA-256").digest(PasskeyUtils.PRF_LABEL + prfInput)
        // Old algorithm: HMAC-SHA-256(HMAC-SHA-256(signingKey, "prf"), prfSalt)
        val oldHmacKey = hmacSha256(signingKeyBytes, "prf".toByteArray(Charsets.UTF_8))
        val oldOutput = hmacSha256(oldHmacKey, prfSalt)

        val newOutput = computeWebAuthnPrf(cryptoGenerator, prfSecretBytes, prfInput)
        assertFalse("New output must differ from old private-key-derived output", newOutput.contentEquals(oldOutput))
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}

private class JvmCryptoGenerator : CryptoGenerator {
    override fun hmac(key: ByteArray, data: ByteArray, algorithm: CryptoHashAlgorithm): ByteArray {
        val name = when (algorithm) {
            CryptoHashAlgorithm.SHA_1 -> "HmacSHA1"
            CryptoHashAlgorithm.SHA_256 -> "HmacSHA256"
            CryptoHashAlgorithm.SHA_512 -> "HmacSHA512"
            CryptoHashAlgorithm.MD5 -> "HmacMD5"
        }
        return Mac.getInstance(name).run {
            init(SecretKeySpec(key, name))
            doFinal(data)
        }
    }

    override fun hashSha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    override fun hashSha1(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(data)

    override fun hashMd5(data: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(data)

    override fun hkdf(seed: ByteArray, salt: ByteArray?, info: ByteArray?, length: Int): ByteArray =
        throw UnsupportedOperationException()

    override fun pbkdf2(seed: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray =
        throw UnsupportedOperationException()

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = throw UnsupportedOperationException()

    override fun seed(length: Int): ByteArray = ByteArray(length)

    override fun uuid(): String = java.util.UUID.randomUUID().toString()

    override fun random(): Int = 0

    override fun random(range: IntRange): Int = range.first
}
