package com.artemchep.keyguard.common.service.download.store

import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.iosKeyguardDataDirectory
import com.artemchep.keyguard.platform.resolve
import org.kodein.di.DirectDI
import org.kodein.di.instance

class DownloadFileStoreIos(
    fileService: FileService,
) : DownloadFileStoreLocalPath(fileService) {
    constructor(
        directDI: DirectDI,
    ) : this(
        fileService = directDI.instance(),
    )

    override suspend fun path(info: DownloadInfoEntity): LocalPath =
        iosKeyguardDataDirectory()
            .resolve("downloads", "${info.id}.bin")
}
