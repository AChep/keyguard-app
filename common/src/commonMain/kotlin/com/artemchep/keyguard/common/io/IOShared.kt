package com.artemchep.keyguard.common.io

import arrow.core.Either
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

/**
 * Returns an IO that computes the resulting value once
 * and only once.
 */
fun <T> IO<T>.shared(
    ifFailedRetryOnNextBind: Boolean = false,
): IO<T> {
    var result: Either<Throwable, T>? = null
    var future: Deferred<Either<Throwable, T>>? = null
    return ioEffect {
        // Fast path out when the resource is already
        // loaded.
        result?.also {
            if (it is Either.Left && ifFailedRetryOnNextBind) {
                // Reset the value, so next time we try to fetch it.
                synchronized(this) {
                    result = null
                    future = null
                }
                return@also
            }
            return@ioEffect it
        }

        synchronized(this) {
            // Start retrieving the value of the original
            // io, that will be shared across all IOs.
            future ?: GlobalScope
                .async {
                    val value = attempt().bind()
                    // Save the result
                    result = value
                    future = null

                    value
                }
                .also {
                    // Theoretically it may NOT happen if the `async`
                    // completes before `also`.
                    if (result == null) {
                        future = it
                    }
                }
        }.await()
    }.flattenMap()
}
