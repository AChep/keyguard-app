package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.common.model.NoAnalytics
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.StringResource

class PasswordMismatchException(
) : RuntimeException("Invalid password"),
    Readable,
    NoAnalytics {
    override val title: StringResource
        get() = Res.strings.error_incorrect_password
}
