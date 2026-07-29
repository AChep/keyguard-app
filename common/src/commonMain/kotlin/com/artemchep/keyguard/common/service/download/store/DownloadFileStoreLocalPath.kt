package com.artemchep.keyguard.common.service.download.store

import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.toFileUriString
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import kotlinx.io.files.SystemFileSystem

abstract class DownloadFileStoreLocalPath : DownloadFileStore {
    protected abstract suspend fun destination(
        info: DownloadInfoEntity,
    ): AtomicFileDestination

    private suspend fun path(info: DownloadInfoEntity): LocalPath =
        destination(info).path

    override suspend fun writer(
        info: DownloadInfoEntity,
    ): DownloadWriter = DownloadWriter.LocalPathWriter(destination(info))

    override suspend fun uri(
        info: DownloadInfoEntity,
    ): String = path(info)
        .toFileUriString()

    override suspend fun exists(
        info: DownloadInfoEntity,
    ): Boolean = SystemFileSystem
        .exists(path(info).toKotlinxIoPath())

    override suspend fun delete(
        info: DownloadInfoEntity,
    ): Boolean = runCatching {
        SystemFileSystem.delete(
            path = path(info).toKotlinxIoPath(),
            mustExist = false,
        )
        true
    }.getOrDefault(false)
}
