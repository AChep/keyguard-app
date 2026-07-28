package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CipherCryptoUriSignatureTest {
    @Test
    fun `login uri signatures are transformed with uri crypto`() {
        val transformed = cipher()
            .transform(
                itemCrypto = prefixEncrypt,
                globalCrypto = prefixEncrypt,
            )

        assertEquals(
            "enc($FINGERPRINT)",
            transformed.login?.uris?.single()?.signatures?.single()?.certFingerprintSha256,
        )
    }

    private fun cipher() = BitwardenCipher(
        accountId = "account-1",
        cipherId = "cipher-1",
        revisionDate = TEST_INSTANT,
        createdDate = TEST_INSTANT,
        service = BitwardenService(),
        name = "Cipher",
        notes = null,
        favorite = false,
        reprompt = BitwardenCipher.RepromptType.None,
        type = BitwardenCipher.Type.Login,
        login = BitwardenCipher.Login(
            uris = listOf(
                BitwardenCipher.Login.Uri(
                    uri = "androidapp://com.example.app",
                    signatures = listOf(
                        BitwardenCipher.Login.Uri.Signature(FINGERPRINT),
                    ),
                ),
            ),
        ),
    )

    private companion object {
        const val FINGERPRINT =
            "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"
        val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
    }
}

private val prefixEnv = BitwardenCrCta.BitwardenCrCtaEnv(
    key = BitwardenCrKey.UserToken,
)

private val prefixEncrypt = PrefixBitwardenCr.cta(
    env = prefixEnv,
    mode = BitwardenCrCta.Mode.ENCRYPT,
)

private object PrefixBitwardenCr : BitwardenCr {
    override val base64Service: Base64Service = PrefixBase64Service

    override fun decoder(
        key: BitwardenCrKey,
    ): (String) -> DecodeResult = { cipherText ->
        DecodeResult(
            data = cipherText.removePrefix("enc(").removeSuffix(")").encodeToByteArray(),
            type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
        )
    }

    override fun encoder(
        key: BitwardenCrKey,
    ): (CipherEncryptor.Type, ByteArray) -> String = { _, data ->
        "enc(${data.decodeToString()})"
    }

    override fun cta(
        env: BitwardenCrCta.BitwardenCrCtaEnv,
        mode: BitwardenCrCta.Mode,
    ): BitwardenCrCta = BitwardenCrCta(
        crypto = this,
        env = env,
        mode = mode,
    )
}

private object PrefixBase64Service : Base64Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = bytes
}
