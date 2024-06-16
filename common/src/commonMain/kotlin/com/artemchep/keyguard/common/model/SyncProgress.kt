package com.artemchep.keyguard.common.model

data class SyncProgress(
    val title: String,
    val progress: Progress? = null,
) {
    data class Progress(
        val at: Int,
        val total: Int,
    )
}

interface SyncScope {
    suspend fun post(
        title: String,
        progress: SyncProgress.Progress? = null,
    )
}
