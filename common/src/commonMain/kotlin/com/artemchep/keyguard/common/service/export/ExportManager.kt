package com.artemchep.keyguard.common.service.export

import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.export.model.ExportRequest
import kotlinx.coroutines.flow.Flow

interface ExportManager {
    /**
     * Returns currently a progress flow for a given [exportId],
     * returns `null` if the export is not active.
     */
    fun getProgressFlowByExportId(exportId: String): Flow<Flow<DownloadProgress>?>

    fun cancel(exportId: String)

    class QueueResult(
        val exportId: String,
        val flow: Flow<DownloadProgress>,
    )

    suspend fun queue(
        request: ExportRequest,
    ): QueueResult
}
