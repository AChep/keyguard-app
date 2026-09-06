package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher

// The GPG chunk wire format is spelled out here on purpose, independently
// of the production constants, so that a format change breaks the tests.
internal fun gpgField(
    name: String,
    value: String,
    type: BitwardenCipher.Field.Type,
) = BitwardenCipher.Field(
    name = name,
    value = value,
    type = type,
)

internal fun gpgChunkPrefix(baseName: String) = "$baseName.v1."

internal fun gpgChunkPartPrefix(baseName: String) = gpgChunkPrefix(baseName) + "part."

internal fun gpgChunkPartName(
    baseName: String,
    index: Int,
) = gpgChunkPartPrefix(baseName) + index

internal fun gpgChunkHashName(baseName: String) = gpgChunkPrefix(baseName) + "sha256"

internal const val GPG_CHUNK_BYTES = 3_500

internal const val GPG_FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF01234567"

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
