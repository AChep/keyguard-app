package com.artemchep.keyguard.common.service.download

import arrow.core.Either
import java.io.File

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
        val result: Either<Throwable, File>,
    ) : DownloadProgress
}
