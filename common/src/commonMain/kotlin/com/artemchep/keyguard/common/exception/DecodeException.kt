package com.artemchep.keyguard.common.exception

class DecodeException(
    message: String,
    exception: Throwable,
) : RuntimeException(message, exception)
