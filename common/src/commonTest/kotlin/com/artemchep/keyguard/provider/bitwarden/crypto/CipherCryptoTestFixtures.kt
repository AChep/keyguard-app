package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.test.testBitwardenCr

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

private val identityBitwardenCr = testBitwardenCr(
    decoder = {
        { cipherText ->
            DecodeResult(
                data = cipherText.encodeToByteArray(),
                type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
            )
        }
    },
    encoder = {
        { _, data ->
            data.decodeToString()
        }
    },
)

internal val identityEncrypt = identityBitwardenCr.cta(
    env = identityEnv,
    mode = BitwardenCrCta.Mode.ENCRYPT,
)

internal val identityDecrypt = identityBitwardenCr.cta(
    env = identityEnv,
    mode = BitwardenCrCta.Mode.DECRYPT,
)
