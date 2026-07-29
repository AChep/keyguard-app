package com.artemchep.keyguard.common.service.download

import kotlinx.coroutines.flow.Flow

interface DownloadTask {
    fun fileLoader(
        data: ByteArray,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress>

    fun fileLoader(
        url: String,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress>
}
