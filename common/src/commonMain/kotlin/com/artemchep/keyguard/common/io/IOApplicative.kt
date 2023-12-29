package com.artemchep.keyguard.common.io

import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

inline fun <T, R> IO<T>.map(
    crossinline block: (T) -> R,
): IO<R> = {
    block(invoke())
}

inline fun <T, R> IO<T>.effectMap(
    crossinline block: suspend (T) -> R,
): IO<R> = {
    block(invoke())
}

@Suppress("FunctionName")
suspend inline fun <T, R> IO<T>._effectMap(
    crossinline block: suspend (T) -> R,
): R = block(invoke())

inline fun <T, R> IO<T>.effectMap(
    context: CoroutineContext,
    crossinline block: suspend (T) -> R,
): IO<R> = suspend {
    val value = invoke() // run previous IO
    withContext(context) {
        block(value)
    }
}

inline fun <T> IO<T>.effectTap(
    context: CoroutineContext = EmptyCoroutineContext,
    crossinline block: suspend (T) -> Unit,
): IO<T> = suspend {
    val value = invoke()
    try {
        withContext(context) {
            block(value)
        }
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
    }
    value
}

inline fun <T> IO<T>.biEffectTap(
    crossinline ifException: suspend (Throwable) -> Unit,
    crossinline ifSuccess: suspend (T) -> Unit,
): IO<T> = biFlatTap(
    ifException = { e ->
        ioEffect { ifException(e) }
    },
    ifSuccess = { value ->
        ioEffect { ifSuccess(value) }
    },
)
