package com.artemchep.keyguard.common.service.download.store

import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.file.toFileUriString
import com.artemchep.keyguard.platform.LocalPath

abstract class DownloadFileStoreLocalPath(
    private val fileService: FileService,
) : DownloadFileStore {
    protected abstract suspend fun path(info: DownloadInfoEntity): LocalPath

    override suspend fun writer(
        info: DownloadInfoEntity,
    ): DownloadWriter = DownloadWriter.LocalPathWriter(path(info))

    override suspend fun uri(
        info: DownloadInfoEntity,
    ): String = path(info)
        .toFileUriString()

    override suspend fun exists(
        info: DownloadInfoEntity,
    ): Boolean = fileService
        .exists(uri(info))

    override suspend fun delete(
        info: DownloadInfoEntity,
    ): Boolean = fileService
        .delete(uri(info))
}
