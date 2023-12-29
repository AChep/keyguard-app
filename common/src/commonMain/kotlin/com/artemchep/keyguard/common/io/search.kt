package com.artemchep.keyguard.common.io

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * A number of queries to search though per
 * coroutine.
 */
private const val PARALLEL_SEARCH_BUCKET_SIZE = 5000

suspend fun <T, R> List<T>.sequentialSearch(
    block: suspend (T) -> R?,
) = this.mapNotNull {
    block(it)
}

suspend fun <T, R> List<T>.parallelSearch(
    block: suspend (T) -> R?,
) =
// We don't want to parallelize the work unless there are
// more than 1 full bucket available.
    if (size >= PARALLEL_SEARCH_BUCKET_SIZE * 2) {
        coroutineScope {
            val bucketSize = size /
                    (size / PARALLEL_SEARCH_BUCKET_SIZE)
                        .coerceAtLeast(1)
                        .coerceAtMost(6) // usually there's less than 6 threads
            this@parallelSearch
                .windowed(
                    size = bucketSize,
                    step = bucketSize,
                    partialWindows = true,
                )
                .map { window ->
                    async(Dispatchers.Default) {
                        window.sequentialSearch(block)
                    }
                }
                .flatMap { it.await() }
        }
    } else {
        sequentialSearch(block)
    }
