package com.artemchep.keyguard.test

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCr
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.DecodeResult

/**
 * Creates a [BitwardenCr] backed by the given functions. The default
 * [decoder] and [encoder] throw as soon as they are requested, so an
 * unexpected call fails loudly.
 */
internal fun testBitwardenCr(
    base64Service: Base64Service = IdentityBase64Service,
    decoder: (BitwardenCrKey) -> (String) -> DecodeResult = {
        error("BitwardenCr.decoder is not used by this test")
    },
    encoder: (BitwardenCrKey) -> (CipherEncryptor.Type, ByteArray) -> String = {
        error("BitwardenCr.encoder is not used by this test")
    },
): BitwardenCr = object : BitwardenCr {
    override val base64Service: Base64Service = base64Service

    override fun decoder(
        key: BitwardenCrKey,
    ): (String) -> DecodeResult = decoder(key)

    override fun encoder(
        key: BitwardenCrKey,
    ): (CipherEncryptor.Type, ByteArray) -> String = encoder(key)

    override fun cta(
        env: BitwardenCrCta.BitwardenCrCtaEnv,
        mode: BitwardenCrCta.Mode,
    ): BitwardenCrCta = BitwardenCrCta(
        crypto = this,
        env = env,
        mode = mode,
    )
}
