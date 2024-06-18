package com.artemchep.keyguard.common.io

import arrow.core.Either
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import java.lang.ref.Reference
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference

/**
 * Returns an IO that computes the resulting value once
 * and only once.
 */
fun <T> IO<T>.shared(
    tag: String,
    ifFailedRetryOnNextBind: Boolean = false,
): IO<T> = sharedRef(
    tag = tag,
    ifFailedRetryOnNextBind = ifFailedRetryOnNextBind,
    wrap = {
        RefStrong(it)
    },
)

fun <T> IO<T>.sharedWeakRef(
    tag: String,
    ifFailedRetryOnNextBind: Boolean = false,
): IO<T> = sharedRef(
    tag = tag,
    ifFailedRetryOnNextBind = ifFailedRetryOnNextBind,
    wrap = {
        val reference = WeakReference(it)
        RefJvm(reference)
    },
)

fun <T> IO<T>.sharedSoftRef(
    tag: String,
    ifFailedRetryOnNextBind: Boolean = false,
): IO<T> = sharedRef(
    tag = tag,
    ifFailedRetryOnNextBind = ifFailedRetryOnNextBind,
    wrap = {
        val reference = SoftReference(it)
        RefJvm(reference)
    },
)

private fun <T> IO<T>.sharedRef(
    tag: String,
    ifFailedRetryOnNextBind: Boolean = false,
    wrap: (Either<Throwable, T>) -> Ref<Either<Throwable, T>>,
): IO<T> {
    var result: Ref<Either<Throwable, T>>? = null
    var future: Deferred<Either<Throwable, T>>? = null
    return ioEffect {
        when (val r = result?.get()) {
            is Either.Left -> {
                if (!ifFailedRetryOnNextBind) {
                    return@ioEffect r
                }
            }

            is Either.Right -> {
                return@ioEffect r
            }

            null -> {
                // Do nothing.
            }
        }

        val prevResultRef = result
        synchronized(this) {
            // Reset the value, so next time we try to fetch it.
            if (prevResultRef === result && result != null) {
                result = null
                future = null
            }

            // Start retrieving the value of the original
            // io, that will be shared across all IOs.
            future ?: GlobalScope
                .async {
                    val value = attempt().bind()
                    // Save the result
                    result = wrap(value)
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

private interface Ref<T> {
    fun get(): T?
}

private class RefJvm<T>(
    private val reference: Reference<T>,
) : Ref<T> {
    override fun get(): T? = reference.get()
}

private class RefStrong<T>(
    private val value: T,
) : Ref<T> {
    override fun get(): T? = value
}
