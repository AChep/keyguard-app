package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.common.model.NoAnalytics
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

class OtpCodeGenerationException(
    e: Throwable? = null,
) : RuntimeException("Failed to generate OTP code", e),
    Readable,
    NoAnalytics {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_failed_generate_otp_code)
}
