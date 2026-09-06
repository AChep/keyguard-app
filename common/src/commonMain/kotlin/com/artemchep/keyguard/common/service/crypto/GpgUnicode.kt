package com.artemchep.keyguard.common.service.crypto

internal fun String.hasValidUnicodeScalars(): Boolean =
    runCatching { encodeToByteArray(throwOnInvalidSequence = true) }.isSuccess
