package com.artemchep.keyguard.common.service.download

import kotlinx.coroutines.flow.Flow

interface DownloadManager {
    fun statusByDownloadId2(downloadId: String): Flow<DownloadProgress>

    fun statusByTag(tag: DownloadInfoEntity.AttachmentDownloadTag): Flow<DownloadProgress>

    class QueueResult(
        val info: DownloadInfoEntity,
        val flow: Flow<DownloadProgress>,
    )

    suspend fun queue(
        downloadInfo: DownloadInfoEntity,
    ): QueueResult

    suspend fun queue(
        request: DownloadQueueRequest,
    ): QueueResult

    suspend fun removeByDownloadId(
        downloadId: String,
    )

    suspend fun removeByTag(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    )
}
