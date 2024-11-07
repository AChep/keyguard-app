package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

class OtpEmptySecretKeyException(
    e: Throwable? = null,
) : IllegalArgumentException("One time password's secret key must not be empty", e), Readable {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_otp_key_must_not_be_empty)
}
