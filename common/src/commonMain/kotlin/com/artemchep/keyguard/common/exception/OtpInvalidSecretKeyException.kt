package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.common.model.NoAnalytics
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

class OtpInvalidSecretKeyException(
    e: Throwable? = null,
) : IllegalArgumentException("One time password's secret key is invalid", e),
    Readable,
    NoAnalytics {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_otp_key_is_invalid)
}
