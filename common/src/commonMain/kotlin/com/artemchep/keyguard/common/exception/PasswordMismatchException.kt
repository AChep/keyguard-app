package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.common.model.NoAnalytics
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

class PasswordMismatchException(
) : RuntimeException("Invalid password"),
    Readable,
    NoAnalytics {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_incorrect_password)
}
