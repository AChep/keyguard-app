package com.artemchep.keyguard.common.service.export

import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.export.model.ExportRequest
import kotlinx.coroutines.flow.Flow

interface ExportManager {
    fun statusByExportId(exportId: String): Flow<DownloadProgress>

    class QueueResult(
        val exportId: String,
        val flow: Flow<DownloadProgress>,
    )

    suspend fun queue(
        request: ExportRequest,
    ): QueueResult
}
