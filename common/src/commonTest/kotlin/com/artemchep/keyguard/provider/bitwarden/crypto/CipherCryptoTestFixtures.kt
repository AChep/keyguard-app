package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.text.Base64Service

private val identityEnv = BitwardenCrCta.BitwardenCrCtaEnv(
    key = BitwardenCrKey.UserToken,
)

internal val identityEncrypt = IdentityBitwardenCr.cta(
    env = identityEnv,
    mode = BitwardenCrCta.Mode.ENCRYPT,
)

internal val identityDecrypt = IdentityBitwardenCr.cta(
    env = identityEnv,
    mode = BitwardenCrCta.Mode.DECRYPT,
)

private object IdentityBitwardenCr : BitwardenCr {
    override val base64Service: Base64Service = IdentityBase64Service

    override fun decoder(
        key: BitwardenCrKey,
    ): (String) -> DecodeResult = { cipherText ->
        DecodeResult(
            data = cipherText.encodeToByteArray(),
            type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
        )
    }

    override fun encoder(
        key: BitwardenCrKey,
    ): (CipherEncryptor.Type, ByteArray) -> String = { _, data ->
        data.decodeToString()
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

private object IdentityBase64Service : Base64Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = bytes
}
