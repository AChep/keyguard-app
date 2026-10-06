package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.common.service.crypto.FileEncryptionCodec
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.artifact.SweepReport
import com.artemchep.keyguard.util.io.artifact.sweepTemporaryArtifacts
import com.artemchep.keyguard.util.io.toLocalPath
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.time.Duration

class EncryptedFilePendingUploadServiceJvm internal constructor(
    dirProvider: PendingUploadDirProvider,
    fileService: FileService,
    fileEncryptionCodec: FileEncryptionCodec,
    stagingSpoolFactory: StagingSpoolFactory = DefaultStagingSpoolFactory(),
    temporarySweeper: (LocalPath, Duration) -> SweepReport = ::sweepTemporaryArtifacts,
    deleteArtifact: (java.nio.file.Path) -> Unit = { path ->
        Files.deleteIfExists(path)
        Unit
    },
) : EncryptedFilePendingUploadService by EncryptedFilePendingUploadServiceImpl(
    dirProvider = dirProvider,
    fileService = fileService,
    fileEncryptionCodec = fileEncryptionCodec,
    fileSystem = PendingUploadFileSystemJvm,
    stagingSpoolFactory = stagingSpoolFactory,
    temporarySweeper = temporarySweeper,
    deleteArtifact = { path -> deleteArtifact(File(path.value).toPath()) },
)

private object PendingUploadFileSystemJvm : PendingUploadFileSystem {
    override fun list(directory: LocalPath): List<LocalPath> = File(directory.value)
        .listFiles()
        .orEmpty()
        .map { file -> file.toLocalPath() }

    override fun isRegularFile(path: LocalPath): Boolean = Files.isRegularFile(
        File(path.value).toPath(),
        LinkOption.NOFOLLOW_LINKS,
    )

    override fun normalizePath(path: String): String = File(path)
        .toPath()
        .toAbsolutePath()
        .normalize()
        .toString()

    override fun deleteIfExists(path: LocalPath) {
        Files.deleteIfExists(File(path.value).toPath())
    }
}
