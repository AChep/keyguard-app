package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoEntity2
import kotlinx.coroutines.flow.Flow

interface DownloadManager {
    fun statusByDownloadId2(downloadId: String): Flow<DownloadProgress>

    fun statusByTag(tag: DownloadInfoEntity2.AttachmentDownloadTag): Flow<DownloadProgress>

    class QueueResult(
        val info: DownloadInfoEntity2,
        val flow: Flow<DownloadProgress>,
    )

    suspend fun queue(
        downloadInfo: DownloadInfoEntity2,
    ): QueueResult

    suspend fun queue(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
        url: String,
        urlIsOneTime: Boolean,
        name: String,
        key: ByteArray? = null,
        attempt: Int = 0,
        worker: Boolean = false,
    ): QueueResult

    suspend fun removeByDownloadId(
        downloadId: String,
    )

    suspend fun removeByTag(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
    )
}
