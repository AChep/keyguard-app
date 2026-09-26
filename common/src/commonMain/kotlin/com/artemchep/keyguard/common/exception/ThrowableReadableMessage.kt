package com.artemchep.keyguard.common.exception

internal expect fun Throwable.readableMessageOrNull(): String?
