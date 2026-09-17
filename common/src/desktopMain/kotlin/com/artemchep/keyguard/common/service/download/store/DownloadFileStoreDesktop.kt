package com.artemchep.keyguard.common.service.download.store

import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.copy.atomicDownloadsDirectory
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent

class DownloadFileStoreDesktop(
    private val dataDirectory: DataDirectory,
) : DownloadFileStoreLocalPath() {

    override suspend fun destination(
        info: DownloadInfoEntity,
    ): AtomicFileDestination = dataDirectory
        .atomicDownloadsDirectory()
        .resolve(AtomicPathComponent.parse(info.name))
}
