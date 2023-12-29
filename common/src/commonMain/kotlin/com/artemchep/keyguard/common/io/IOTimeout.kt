package com.artemchep.keyguard.common.io

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeoutException

/**
 * Raises the exception in IO, if the execution takes
 * more than [millis].
 */
fun <T> IO<T>.timeout(millis: Long): IO<T> = {
    try {
        withTimeout(millis) {
            invoke()
        }
    } catch (e: TimeoutCancellationException) {
        throw TimeoutException()
    }
}
