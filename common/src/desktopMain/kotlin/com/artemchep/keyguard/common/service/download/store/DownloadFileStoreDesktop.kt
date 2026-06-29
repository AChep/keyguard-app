package com.artemchep.keyguard.common.service.download.store

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toLocalPath
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.File

class DownloadFileStoreDesktop(
    private val dataDirectory: DataDirectory,
    fileService: FileService,
) : DownloadFileStoreLocalPath(fileService) {
    constructor(
        directDI: DirectDI,
    ) : this(
        dataDirectory = directDI.instance(),
        fileService = directDI.instance(),
    )

    override suspend fun path(
        info: DownloadInfoEntity,
    ): LocalPath = dataDirectory
        .downloads()
        .bind()
        .let(::File)
        .resolve(info.name)
        .toLocalPath()
}
