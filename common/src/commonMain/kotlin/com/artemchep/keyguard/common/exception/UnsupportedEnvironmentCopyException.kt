package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.common.model.NoAnalytics
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

class UnsupportedEnvironmentCopyException : IllegalArgumentException(
    "Cannot copy an unsupported environment to another account",
),
    Readable,
    NoAnalytics {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.ciphers_copy_error_unsupported_environment)
}
