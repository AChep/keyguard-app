package com.artemchep.keyguard.feature.home.vault.search.engine

interface SearchExecutor {
    suspend fun <T, R> map(
        values: List<T>,
        block: suspend (T) -> R,
    ): List<R>
}
