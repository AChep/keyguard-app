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
            privateKeyBytes = "private-key".toByteArray(),
            prfInput = "prf-input".toByteArray(),
        )
        org.junit.Assert.assertEquals(32, output.size)
    }

    @Test
    fun `computeWebAuthnPrf is deterministic for the same inputs`() {
        val privateKeyBytes = "private-key".toByteArray()
        val prfInput = "prf-input".toByteArray()
        val output1 = computeWebAuthnPrf(cryptoGenerator, privateKeyBytes, prfInput)
        val output2 = computeWebAuthnPrf(cryptoGenerator, privateKeyBytes, prfInput)
        assertArrayEquals(output1, output2)
    }

    @Test
    fun `computeWebAuthnPrf different inputs produce different outputs`() {
        val privateKeyBytes = "private-key".toByteArray()
        val output1 = computeWebAuthnPrf(cryptoGenerator, privateKeyBytes, "input-a".toByteArray())
        val output2 = computeWebAuthnPrf(cryptoGenerator, privateKeyBytes, "input-b".toByteArray())
        assertFalse(output1.contentEquals(output2))
    }

    @Test
    fun `computeWebAuthnPrf different keys produce different outputs`() {
        val prfInput = "prf-input".toByteArray()
        val output1 = computeWebAuthnPrf(cryptoGenerator, "key-1".toByteArray(), prfInput)
        val output2 = computeWebAuthnPrf(cryptoGenerator, "key-2".toByteArray(), prfInput)
        assertFalse(output1.contentEquals(output2))
    }

    @Test
    fun `computeWebAuthnPrf matches expected computation`() {
        val privateKeyBytes = "test-private-key".toByteArray(Charsets.UTF_8)
        val prfInput = "test-prf-input".toByteArray(Charsets.UTF_8)

        // Manually compute the expected output using the same algorithm
        val prfSaltInput = PasskeyUtils.PRF_LABEL + prfInput
        val prfSalt = MessageDigest.getInstance("SHA-256").digest(prfSaltInput)
        val hmacKey = hmacSha256(privateKeyBytes, "prf".toByteArray(Charsets.UTF_8))
        val expected = hmacSha256(hmacKey, prfSalt)

        val actual = computeWebAuthnPrf(cryptoGenerator, privateKeyBytes, prfInput)
        assertArrayEquals(expected, actual)
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
