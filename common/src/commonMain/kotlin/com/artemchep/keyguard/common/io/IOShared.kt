package com.artemchep.keyguard.common.io

import arrow.core.Either
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

fun <T> IO<T>.sharedSoftRef(
    tag: String,
    ifFailedRetryOnNextBind: Boolean = false,
): IO<T> = sharedRef(
    tag = tag,
    ifFailedRetryOnNextBind = ifFailedRetryOnNextBind,
    wrap = {
        refSoft(it)
    },
)

@OptIn(DelicateCoroutinesApi::class)
private fun <T> IO<T>.sharedRef(
    tag: String,
    ifFailedRetryOnNextBind: Boolean = false,
    wrap: (Either<Throwable, T>) -> Ref<Either<Throwable, T>>,
): IO<T> {
    val mutex = Mutex()
    var result: Ref<Either<Throwable, T>>? = null
    var future: Deferred<Either<Throwable, T>>? = null

    suspend fun getOrStart(): Deferred<Either<Throwable, T>> =
        mutex.withLock {
            when (val r = result?.get()) {
                is Either.Left -> {
                    if (!ifFailedRetryOnNextBind) {
                        return@withLock CompletableDeferred(r)
                    }

                    result = null
                }

                is Either.Right -> {
                    return@withLock CompletableDeferred(r)
                }

                null -> {
                    if (result != null) {
                        result = null
                    }
                }
            }

            future ?: run {
                var created: Deferred<Either<Throwable, T>>? = null
                val deferred = GlobalScope
                    .async(
                        context = CoroutineName("IO.shared($tag)"),
                        start = CoroutineStart.LAZY,
                    ) {
                        try {
                            val value = attempt().bind()
                            // Save the result.
                            mutex.withLock {
                                if (future === created) {
                                    result = wrap(value)
                                    future = null
                                }
                            }

                            value
                        } catch (e: Throwable) {
                            mutex.withLock {
                                if (future === created) {
                                    future = null
                                }
                            }

                            throw e
                        }
                    }
                created = deferred
                future = deferred
                deferred.start()
                deferred
            }
        }

    return ioEffect {
        getOrStart().await()
    }.flattenMap()
}

internal interface Ref<T : Any> {
    fun get(): T?
}

internal class RefStrong<T : Any>(
    private val value: T,
) : Ref<T> {
    override fun get(): T? = value
}

internal expect fun <T : Any> refSoft(value: T): Ref<T>
