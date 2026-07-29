package com.artemchep.keyguard.common.service.download.store

import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.copy.atomicDownloadsDirectory
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import org.kodein.di.DirectDI
import org.kodein.di.instance

class DownloadFileStoreDesktop(
    private val dataDirectory: DataDirectory,
) : DownloadFileStoreLocalPath() {
    constructor(
        directDI: DirectDI,
    ) : this(
        dataDirectory = directDI.instance(),
    )

    override suspend fun destination(
        info: DownloadInfoEntity,
    ): AtomicFileDestination = dataDirectory
        .atomicDownloadsDirectory()
        .resolve(AtomicPathComponent.parse(info.name))
}
