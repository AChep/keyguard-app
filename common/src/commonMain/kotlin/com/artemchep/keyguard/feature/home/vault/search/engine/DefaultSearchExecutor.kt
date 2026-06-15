package com.artemchep.keyguard.feature.home.vault.search.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private const val SEARCH_PARALLEL_BUCKET_SIZE = 2048
private const val MAX_SEARCH_WORKERS = 6

class DefaultSearchExecutor : SearchExecutor {
    override suspend fun <T, R> map(
        values: List<T>,
        block: suspend (T) -> R,
    ): List<R> {
        if (values.size < SEARCH_PARALLEL_BUCKET_SIZE * 2) {
            return values.map { value ->
                block(value)
            }
        }

        return coroutineScope {
            val workers = (values.size / SEARCH_PARALLEL_BUCKET_SIZE)
                .coerceAtLeast(1)
                .coerceAtMost(MAX_SEARCH_WORKERS)
            val chunkSize = ((values.size + workers - 1) / workers)
                .coerceAtLeast(1)
            val deferred = values
                .chunked(chunkSize)
                .map { chunk ->
                    async(Dispatchers.Default) {
                        chunk.map { value ->
                            block(value)
                        }
                    }
                }
            deferred.flatMap { it.await() }
        }
    }
}
