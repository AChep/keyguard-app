package com.artemchep.keyguard.common.io

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

fun <T> List<IO<T>>.combine(
    bucket: Int,
    context: CoroutineContext = EmptyCoroutineContext,
): IO<List<T>> = ioEffect {
    if (size >= bucket * 2) {
        coroutineScope {
            val bucketSize = size /
                    (size / bucket)
                        .coerceAtLeast(1)
            this@combine
                .windowed(
                    size = bucketSize,
                    step = bucketSize,
                    partialWindows = true,
                )
                .map { window ->
                    async(context) {
                        sequentialCombine(window)
                    }
                }
                .flatMap { it.await() }
        }
    } else {
        sequentialCombine(this)
    }
}

fun <T> List<IO<T>>.combineSeq(
    context: CoroutineContext = EmptyCoroutineContext,
): IO<List<T>> = ioEffect(context = context) {
    sequentialCombine(this)
}

private suspend fun <T> sequentialCombine(
    list: List<IO<T>>,
): List<T> = list
    .map { io -> io.bind() }

//
// Small number of params
//

fun <A, B, T> combineIo(
    a: IO<A>,
    b: IO<B>,
    combine: suspend (A, B) -> T,
): IO<T> = ioEffect {
    val (aValue, bValue) = coroutineScope {
        val aAsync = async { a.bind() }
        val bAsync = async { b.bind() }
        aAsync.await() to bAsync.await()
    }
    combine(aValue, bValue)
}
