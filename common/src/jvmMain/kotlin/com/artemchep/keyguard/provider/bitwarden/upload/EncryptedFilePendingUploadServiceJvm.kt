package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.platform.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.kodein.di.DirectDI
import org.kodein.di.instance

class EncryptedFilePendingUploadServiceJvm(
    private val dirProvider: PendingUploadDirProvider,
    private val fileService: FileService,
    private val fileEncryptor: FileEncryptor,
) : EncryptedFilePendingUploadService {
    constructor(
        directDI: DirectDI,
    ) : this(
        dirProvider = directDI.instance(),
        fileService = directDI.instance(),
        fileEncryptor = directDI.instance(),
    )

    override suspend fun stage(
        accountId: String,
        namespace: String,
        fileId: String,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile = withContext(Dispatchers.IO) {
        val dir = dirProvider.get(
            accountId = accountId,
            namespace = namespace,
        )
        val finalPath = dir.resolve("$fileId.bin")
        val tempPath = dir.resolve("$fileId.bin.tmp")
        val finalFile = File(finalPath.value)
        val tempFile = File(tempPath.value)

        tempFile.parentFile?.mkdirs()
        tempFile.delete()

        try {
            val result = fileService
                .readFromFile(sourceUri)
                .use { source ->
                    fileEncryptor.encode(
                        input = source,
                        output = tempPath,
                        key = fileKey,
                    )
                }

            finalFile.parentFile?.mkdirs()
            if (finalFile.exists()) {
                finalFile.delete()
            }
            Files.move(
                tempFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            runCatching {
                uploadedMarkerFile(finalFile.path).delete()
            }
            runCatching {
                fileService.deleteManagedSourceFile(sourceUri)
            }

            return@withContext PendingUploadFile(
                path = finalPath.value,
                plainSize = result.plainSize,
                encryptedSize = result.encryptedSize,
            )
        } catch (e: Throwable) {
            runCatching {
                tempFile.delete()
            }
            throw e
        }
    }

    override suspend fun delete(
        pendingUpload: PendingUploadFile,
    ): Unit = withContext(Dispatchers.IO) {
        fileService.delete(File(pendingUpload.path).toURI().toString())
        fileService.delete(File("${pendingUpload.path}.tmp").toURI().toString())
        fileService.delete(uploadedMarkerFile(pendingUpload).toURI().toString())
    }

    override suspend fun markUploaded(
        pendingUpload: PendingUploadFile,
    ): Unit = withContext(Dispatchers.IO) {
        val markerFile = uploadedMarkerFile(pendingUpload)
        markerFile.parentFile?.mkdirs()
        markerFile.writeText("")
    }

    override suspend fun isUploaded(
        pendingUpload: PendingUploadFile,
    ): Boolean = withContext(Dispatchers.IO) {
        uploadedMarkerFile(pendingUpload).exists()
    }

    private fun uploadedMarkerFile(
        pendingUpload: PendingUploadFile,
    ) = uploadedMarkerFile(pendingUpload.path)

    private fun uploadedMarkerFile(
        path: String,
    ) = File("$path.uploaded")
}
