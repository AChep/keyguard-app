package com.artemchep.keyguard.common.io

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

fun <T> List<IO<T>>.parallel(
    context: CoroutineContext = EmptyCoroutineContext,
    parallelism: Int = 4,
): IO<List<T>> = {
    val window = size.div(parallelism).coerceAtLeast(1)
    coroutineScope {
        this@parallel
            .windowed(
                size = window,
                step = window,
                partialWindows = true,
            )
            .map { list ->
                async(context) {
                    list.map { io -> io.bind() }
                }
            }
            .flatMap { it.await() }
    }
}

inline fun <T, R> IO<T>.flatMap(
    crossinline block: (T) -> IO<R>,
): IO<R> = suspend {
    val io = block(invoke())
    io()
}

inline fun <T> IO<T>.flatTap(
    crossinline block: (T) -> IO<Any?>,
): IO<T> = {
    val value = invoke()
    val io = block(value)
    try {
        io()
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
    }
    value
}

inline fun <T> IO<T>.biFlatTap(
    crossinline ifException: (Throwable) -> IO<Any?>,
    crossinline ifSuccess: (T) -> IO<Any?>,
): IO<T> = suspend {
    val (identity, io) = try {
        val v = invoke()
        io(v) to ifSuccess(v)
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
        ioRaise<T>(e) to ifException(e)
    }
    // execute the "tap" io
    try {
        io()
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
    }
    identity
}.flatten()
