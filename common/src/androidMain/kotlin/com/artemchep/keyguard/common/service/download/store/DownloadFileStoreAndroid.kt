package com.artemchep.keyguard.common.service.download.store

import android.app.Application
import android.content.Context
import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.toLocalPath
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.File

class DownloadFileStoreAndroid(
    private val context: Context,
) : DownloadFileStoreLocalPath() {
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
    )

    override suspend fun destination(
        info: DownloadInfoEntity,
    ): AtomicFileDestination = AtomicFileDestination(
        root = context.filesDir.toLocalPath(),
        relativePath = AtomicRelativePath.fromComponents(
            AtomicPathComponent.parse("downloads"),
            AtomicPathComponent.parse("${info.id}$CACHE_FILE_EXT"),
        ),
    )
}
