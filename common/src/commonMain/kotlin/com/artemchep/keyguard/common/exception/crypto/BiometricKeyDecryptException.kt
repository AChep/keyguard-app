package com.artemchep.keyguard.common.exception.crypto

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

class BiometricKeyDecryptException(
    e: Throwable,
) : RuntimeException("Failed to decrypt the biometric key", e), Readable {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_failed_decrypt_biometric_key)

    override val text: TextHolder?
        get() = cause?.message?.let(TextHolder::Value)
}
