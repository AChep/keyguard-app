package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.provider.bitwarden.repository.BaseRepository
import kotlinx.coroutines.flow.Flow

interface DownloadRepository : BaseRepository<DownloadInfoEntity> {
    fun getById(
        id: String,
    ): IO<DownloadInfoEntity?>

    fun getByIdFlow(
        id: String,
    ): Flow<DownloadInfoEntity?>

    fun getByTag(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    ): IO<DownloadInfoEntity?>

    fun getByTagFlow(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    ): Flow<DownloadInfoEntity?>

    fun removeById(
        id: String,
    ): IO<Unit>

    fun removeByTag(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    ): IO<Unit>
}
