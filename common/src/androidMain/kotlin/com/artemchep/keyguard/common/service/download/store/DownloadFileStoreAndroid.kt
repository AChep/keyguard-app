package com.artemchep.keyguard.common.service.download.store

import android.app.Application
import android.content.Context
import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toLocalPath
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.File

class DownloadFileStoreAndroid(
    private val context: Context,
    fileService: FileService,
) : DownloadFileStoreLocalPath(fileService) {
    companion object {
        // Since the extension of the files is unknown it's safe
        // to say that they are just binaries.
        private const val CACHE_FILE_EXT = ".bin"

        fun getDir(context: Context) = context.filesDir.resolve("downloads/")

        fun getFile(
            dir: File,
            downloadId: String,
        ) = dir.resolve("$downloadId$CACHE_FILE_EXT")
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
        fileService = directDI.instance(),
    )

    override suspend fun path(
        info: DownloadInfoEntity,
    ): LocalPath = getFile(
        dir = getDir(context),
        downloadId = info.id,
    ).toLocalPath()
}
