package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.common.service.crypto.FileEncryptionCodec
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.delete
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import kotlinx.io.files.SystemFileSystem

class EncryptedFilePendingUploadServiceApple internal constructor(
    dirProvider: PendingUploadDirProvider,
    fileService: FileService,
    fileEncryptionCodec: FileEncryptionCodec,
    stagingSpoolFactory: StagingSpoolFactory = DefaultStagingSpoolFactory(),
) : EncryptedFilePendingUploadService by EncryptedFilePendingUploadServiceImpl(
    dirProvider = dirProvider,
    fileService = fileService,
    fileEncryptionCodec = fileEncryptionCodec,
    fileSystem = PendingUploadFileSystemApple,
    stagingSpoolFactory = stagingSpoolFactory,
)

private object PendingUploadFileSystemApple : PendingUploadFileSystem {
    override fun list(directory: LocalPath): List<LocalPath> {
        val path = directory.toKotlinxIoPath()
        if (!SystemFileSystem.exists(path)) return emptyList()
        return SystemFileSystem.list(path).map { child -> LocalPath(child.toString()) }
    }

    override fun isRegularFile(path: LocalPath): Boolean =
        SystemFileSystem.metadataOrNull(path.toKotlinxIoPath())?.isRegularFile == true

    /** Apple upload paths are absolute; normalize their lexical spelling without resolving links. */
    override fun normalizePath(path: String): String {
        val segments = mutableListOf<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull()
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/", prefix = "/")
    }

    override fun deleteIfExists(path: LocalPath) {
        path.delete()
    }
}
