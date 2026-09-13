package com.artemchep.keyguard.common.exception

import io.ktor.client.engine.darwin.DarwinHttpRequestException

internal actual fun Throwable.readableMessageOrNull(): String? = when (this) {
    // Ktor's message contains NSError's diagnostic dump, including task IDs.
    is DarwinHttpRequestException -> origin.localizedDescription
    else -> message
}
