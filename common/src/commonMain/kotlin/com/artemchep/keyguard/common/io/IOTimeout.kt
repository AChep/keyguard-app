package com.artemchep.keyguard.common.io

import kotlinx.coroutines.withTimeoutOrNull

class IOTimeoutException(
    message: String?,
    cause: Throwable?,
) : Exception(message, cause)

/**
 * Raises the exception in IO, if the execution takes
 * more than [millis].
 */
fun <T> IO<T>.timeout(millis: Long): IO<T> = {
    val result = withTimeoutOrNull(millis) {
        TimeoutValue(invoke())
    }

    if (result != null) {
        result.value
    } else {
        throw IOTimeoutException("Timed out waiting for $millis ms", null)
    }
}

private class TimeoutValue<T>(
    val value: T,
)
