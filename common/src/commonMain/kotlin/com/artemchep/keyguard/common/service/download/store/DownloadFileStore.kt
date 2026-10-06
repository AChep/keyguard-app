package com.artemchep.keyguard.common.service.download.store

import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.common.service.download.DownloadWriter

/**
 * Owns the storage mapping for downloaded attachment files. Platform implementations
 * decide the actual filesystem location and naming strategy.
 */
interface DownloadFileStore {
    suspend fun writer(info: DownloadInfoEntity): DownloadWriter

    suspend fun uri(info: DownloadInfoEntity): String

    /** Resolves a successful writer result for external use; most stores expose it directly. */
    suspend fun completedUri(info: DownloadInfoEntity, writerUri: String?): String? = writerUri

    suspend fun exists(info: DownloadInfoEntity): Boolean

    suspend fun delete(info: DownloadInfoEntity): Boolean
}
