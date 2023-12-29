package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoEntity2
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.provider.bitwarden.repository.BaseRepository
import kotlinx.coroutines.flow.Flow

interface DownloadRepository : BaseRepository<DownloadInfoEntity2> {
    fun getById(
        id: String,
    ): IO<DownloadInfoEntity2?>

    fun getByIdFlow(
        id: String,
    ): Flow<DownloadInfoEntity2?>

    fun getByTag(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
    ): IO<DownloadInfoEntity2?>

    fun removeById(
        id: String,
    ): IO<Unit>

    fun removeByTag(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
    ): IO<Unit>
}
