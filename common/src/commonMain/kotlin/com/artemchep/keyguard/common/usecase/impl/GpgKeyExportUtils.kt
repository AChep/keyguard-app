package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.DateFormatter
import kotlin.time.Clock

internal fun String.gpgFileSafeSuffix(): String =
    filter(Char::isLetterOrDigit)
        .takeLast(16)
        .ifBlank { "key" }

internal fun DateFormatter.gpgExportDateSuffix(): String =
    formatDateTimeMachine(Clock.System.now())
