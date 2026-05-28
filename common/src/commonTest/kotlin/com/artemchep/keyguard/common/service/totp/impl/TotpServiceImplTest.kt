package com.artemchep.keyguard.common.service.totp.impl

import arrow.core.Either
import com.artemchep.keyguard.common.exception.OtpCodeGenerationException
import com.artemchep.keyguard.common.exception.OtpEmptySecretKeyException
import com.artemchep.keyguard.common.exception.OtpInvalidSecretKeyException
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base32Service
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TotpServiceImplTest {
    @Test
    fun `generate returns Right for valid totp token`() {
        val service = TotpServiceImpl(
            base32Service = FakeBase32Service(
                decodedValues = mapOf(
                    "valid" to byteArrayOf(0x01),
                ),
            ),
            cryptoGenerator = FakeCryptoGenerator(
                hmacResult = HOTP_HASH_123456,
            ),
        )

        val result = service.generate(
            token = createTotpToken("valid"),
            timestamp = TEST_INSTANT,
        )

        val right = assertIs<Either.Right<TotpCode>>(result)
        assertEquals("123456", right.value.code)
    }

    @Test
    fun `generate returns empty secret exception for blank secret`() {
        val service = TotpServiceImpl(
            base32Service = FakeBase32Service(),
            cryptoGenerator = FakeCryptoGenerator(),
        )

        val result = service.generate(
            token = createTotpToken(""),
            timestamp = TEST_INSTANT,
        )

        val left = assertIs<Either.Left<Throwable>>(result)
        assertIs<OtpEmptySecretKeyException>(left.value)
    }

    @Test
    fun `generate returns invalid secret exception when decoded key is empty`() {
        val service = TotpServiceImpl(
            base32Service = FakeBase32Service(
                decodedValues = mapOf(
                    "invalid" to byteArrayOf(),
                ),
            ),
            cryptoGenerator = FakeCryptoGenerator(),
        )

        val result = service.generate(
            token = createTotpToken("invalid"),
            timestamp = TEST_INSTANT,
        )

        val left = assertIs<Either.Left<Throwable>>(result)
        assertIs<OtpInvalidSecretKeyException>(left.value)
    }

    @Test
    fun `generate wraps unexpected crypto failures`() {
        val cause = IllegalStateException("boom")
        val service = TotpServiceImpl(
            base32Service = FakeBase32Service(
                decodedValues = mapOf(
                    "valid" to byteArrayOf(0x01),
                ),
            ),
            cryptoGenerator = FakeCryptoGenerator(
                hmacThrowable = cause,
            ),
        )

        val result = service.generate(
            token = createTotpToken("valid"),
            timestamp = TEST_INSTANT,
        )

        val left = assertIs<Either.Left<Throwable>>(result)
        val error = assertIs<OtpCodeGenerationException>(left.value)
        assertSame(cause, error.cause)
    }

    @Test
    fun `generate rejects ten digit totp token`() {
        val service = createService()

        val result = service.generate(
            token = createTotpToken("valid", digits = 10),
            timestamp = TEST_INSTANT,
        )

        val left = assertIs<Either.Left<Throwable>>(result)
        val error = assertIs<OtpCodeGenerationException>(left.value)
        assertIs<IllegalArgumentException>(error.cause)
    }

    @Test
    fun `generate rejects ten digit hotp token`() {
        val service = createService()

        val result = service.generate(
            token = createHotpToken("valid", digits = 10),
            timestamp = TEST_INSTANT,
        )

        val left = assertIs<Either.Left<Throwable>>(result)
        val error = assertIs<OtpCodeGenerationException>(left.value)
        assertIs<IllegalArgumentException>(error.cause)
    }
}

private fun createService() = TotpServiceImpl(
    base32Service = FakeBase32Service(
        decodedValues = mapOf(
            "valid" to byteArrayOf(0x01),
        ),
    ),
    cryptoGenerator = FakeCryptoGenerator(
        hmacResult = HOTP_HASH_123456,
    ),
)

private fun createTotpToken(
    keyBase32: String,
    digits: Int = 6,
) = TotpToken.TotpAuth(
    algorithm = CryptoHashAlgorithm.SHA_1,
    keyBase32 = keyBase32,
    raw = keyBase32,
    digits = digits,
    period = 30L,
)

private fun createHotpToken(
    keyBase32: String,
    digits: Int = 6,
) = TotpToken.HotpAuth(
    algorithm = CryptoHashAlgorithm.SHA_1,
    keyBase32 = keyBase32,
    raw = keyBase32,
    digits = digits,
    counter = 0L,
)

private class FakeBase32Service(
    private val decodedValues: Map<String, ByteArray> = emptyMap(),
) : Base32Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = decodedValues[String(bytes)]
        ?: bytes
}

private class FakeCryptoGenerator(
    private val hmacResult: ByteArray = HOTP_HASH_123456,
    private val hmacThrowable: Throwable? = null,
) : CryptoGenerator {
    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = byteArrayOf()

    override fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = byteArrayOf()

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = byteArrayOf()

    override fun seed(length: Int): ByteArray = byteArrayOf()

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray {
        hmacThrowable?.let { throw it }
        return hmacResult
    }

    override fun hashSha1(data: ByteArray): ByteArray = byteArrayOf()

    override fun hashSha256(data: ByteArray): ByteArray = byteArrayOf()

    override fun hashMd5(data: ByteArray): ByteArray = byteArrayOf()

    override fun uuid(): String = "uuid"

    override fun random(): Int = 0

    override fun random(range: IntRange): Int = range.first
}

private val HOTP_HASH_123456 = byteArrayOf(
    0x00,
    0x01,
    0xE2.toByte(),
    0x40,
    0x00,
    0x00,
    0x00,
    0x00,
    0x00,
    0x00,
    0x00,
    0x00,
    0x00,
    0x00,
    0x00,
    0x00,
    0x00,
    0x00,
    0x00,
    0x00,
)

private val TEST_INSTANT = Instant.fromEpochSeconds(1_700_000_000)
