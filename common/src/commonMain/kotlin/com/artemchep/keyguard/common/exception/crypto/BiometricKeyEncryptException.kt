package com.artemchep.keyguard.common.exception.crypto

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

class BiometricKeyEncryptException(
    e: Throwable,
) : RuntimeException("Failed to encrypt the biometric key", e), Readable {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_failed_encrypt_biometric_key)

    override val text: TextHolder?
        get() = cause?.message?.let(TextHolder::Value)
}
