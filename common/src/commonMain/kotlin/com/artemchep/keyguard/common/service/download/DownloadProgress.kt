package com.artemchep.keyguard.common.service.download

import arrow.core.Either
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.last

sealed interface DownloadProgress {
    data object None : DownloadProgress

    data class Loading(
        val downloaded: Long? = null,
        val total: Long? = null,
    ) : DownloadProgress {
        val percentage: Float? =
            if (downloaded != null && total != null) {
                val p = downloaded.toDouble() / total.toDouble()
                p.toFloat().coerceIn(0f..1f)
            } else {
                null
            }
    }

    data class Complete(
        val result: Either<Throwable, String?>,
    ) : DownloadProgress
}

/**
 * Awaits the terminal event of a download flow and returns its result.
 * Fails if the flow finishes without a [DownloadProgress.Complete] event.
 */
suspend fun Flow<DownloadProgress>.awaitCompleteResult(): Either<Throwable, String?> {
    val complete = last()
    check(complete is DownloadProgress.Complete) {
        "Download did not complete."
    }
    return complete.result
}
