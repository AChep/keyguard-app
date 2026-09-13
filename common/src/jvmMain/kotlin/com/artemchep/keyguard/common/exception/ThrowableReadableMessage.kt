package com.artemchep.keyguard.common.exception

internal actual fun Throwable.readableMessageOrNull(): String? = message
