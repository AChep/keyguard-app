package com.artemchep.keyguard.common.io

import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Creates an [IO] that returns given
 * value.
 */
fun <T> io(value: T): IO<T> = ioEffect {
    value
}

fun <T> ioRaise(throwable: Throwable): IO<T> = ioEffect {
    throw throwable
}

private val ioUnit = io(Unit)

/**
 * Creates an [IO] that returns [Unit].
 */
fun ioUnit(): IO<Unit> = ioUnit

inline fun <T> ioEffect(
    crossinline block: suspend () -> T,
): IO<T> = suspend {
    block()
}

inline fun <T> ioEffect(
    context: CoroutineContext,
    crossinline block: suspend () -> T,
): IO<T> = ioEffect {
    withContext(context) {
        block()
    }
}
