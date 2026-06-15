package com.artemchep.keyguard.common.service.webauthn

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebAuthnAuthenticatorDataFactoryTest {
    @Test
    fun `authenticator data rejects negative sign count`() {
        val factory = WebAuthnAuthenticatorDataFactory(
            cryptoService = FakeCryptoGenerator,
        )

        val error = assertFailsWith<IllegalArgumentException> {
            factory.encodeAuthenticatorData(
                rpId = "example.com",
                signCount = -1,
                credentialId = byteArrayOf(0x01),
                credentialPublicKey = null,
                userVerified = true,
                userPresent = true,
            )
        }

        assertEquals(
            "WebAuthn signCount must be non-negative.",
            error.message,
        )
    }
}

private object FakeCryptoGenerator : CryptoGenerator {
    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = error("Not used in this test.")

    override fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = error("Not used in this test.")

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = error("Not used in this test.")

    override fun seed(
        length: Int,
    ): ByteArray = error("Not used in this test.")

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray = error("Not used in this test.")

    override fun hashSha1(
        data: ByteArray,
    ): ByteArray = error("Not used in this test.")

    override fun hashSha256(
        data: ByteArray,
    ): ByteArray = ByteArray(32)

    override fun hashMd5(
        data: ByteArray,
    ): ByteArray = error("Not used in this test.")

    override fun uuid(): String = error("Not used in this test.")

    override fun random(): Int = error("Not used in this test.")

    override fun random(
        range: IntRange,
    ): Int = error("Not used in this test.")
}
