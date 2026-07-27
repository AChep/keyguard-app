package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.crypto.decode
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.file.fileLastModifiedMillis
import com.artemchep.keyguard.platform.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import kotlin.time.Instant
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
        val finalPath = dir.resolve("$fileId$PENDING_UPLOAD_SUFFIX")
        val tempPath = dir.resolve("$fileId$PENDING_UPLOAD_TEMP_SUFFIX")
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
        artifactFiles(pendingUpload.path).forEach { artifact ->
            fileService.delete(artifact.toURI().toString())
        }
    }

    override suspend fun readPlaintext(
        pendingUpload: PendingUploadFile,
        fileKey: ByteArray,
    ): ByteArray = withContext(Dispatchers.IO) {
        File(pendingUpload.path)
            .inputStream()
            .use { encryptedInput ->
                fileEncryptor
                    .decode(encryptedInput, fileKey)
                    .use { plaintextInput ->
                        plaintextInput.readBytes()
                    }
            }
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

    override suspend fun sweepOrphans(
        accountId: String,
        namespace: String,
        referencedPaths: Set<String>,
        olderThan: Instant,
    ): Unit = withContext(Dispatchers.IO) {
        val dir = dirProvider.get(
            accountId = accountId,
            namespace = namespace,
        )
        val normalizedReferencedPaths = referencedPaths
            .mapTo(mutableSetOf(), ::normalizePath)

        File(dir.value)
            .listFiles()
            .orEmpty()
            .asSequence()
            // Match on the name before touching the filesystem, so unrelated
            // files in the directory never cost a stat.
            .mapNotNull { file ->
                pendingUploadBasePathOrNull(file)
                    ?.let { basePath -> basePath to file }
            }
            .filter { (_, file) ->
                Files.isRegularFile(
                    file.toPath(),
                    LinkOption.NOFOLLOW_LINKS,
                )
            }
            .groupBy(
                keySelector = { (basePath, _) -> basePath },
                valueTransform = { (_, file) -> file },
            )
            .filterKeys { basePath -> basePath !in normalizedReferencedPaths }
            .values
            .filter { artifacts ->
                artifacts.all { artifact -> artifact.isStaleAt(olderThan) }
            }
            .flatten()
            .forEach { artifact ->
                runCatching {
                    Files.deleteIfExists(artifact.toPath())
                }
            }
    }

    /**
     * Treats an artifact whose timestamp cannot be read as not stale, so an
     * unreadable file is kept rather than deleted.
     */
    private fun File.isStaleAt(
        olderThan: Instant,
    ): Boolean {
        val lastModifiedMillis = fileLastModifiedMillis(path)
            ?: return false
        return lastModifiedMillis <= olderThan.toEpochMilliseconds()
    }

    private fun uploadedMarkerFile(
        pendingUpload: PendingUploadFile,
    ) = uploadedMarkerFile(pendingUpload.path)

    private fun uploadedMarkerFile(
        path: String,
    ) = File("$path$MARKER_EXTENSION")

    /**
     * Every file that makes up one staged upload. The orphan sweep recognizes
     * the same group by matching these suffixes.
     */
    private fun artifactFiles(
        basePath: String,
    ) = listOf(
        File(basePath),
        File("$basePath$TEMP_EXTENSION"),
        uploadedMarkerFile(basePath),
    )

    private fun pendingUploadBasePathOrNull(
        file: File,
    ): String? {
        val basePath = when {
            file.name.endsWith(PENDING_UPLOAD_TEMP_SUFFIX) ->
                file.path.removeSuffix(TEMP_EXTENSION)

            file.name.endsWith(PENDING_UPLOAD_MARKER_SUFFIX) ->
                file.path.removeSuffix(MARKER_EXTENSION)

            file.name.endsWith(PENDING_UPLOAD_SUFFIX) ->
                file.path

            else -> return null
        }
        return normalizePath(basePath)
    }

    private fun normalizePath(
        path: String,
    ): String = File(path)
        .toPath()
        .toAbsolutePath()
        .normalize()
        .toString()

    private companion object {
        const val TEMP_EXTENSION = ".tmp"
        const val MARKER_EXTENSION = ".uploaded"
        const val PENDING_UPLOAD_SUFFIX = ".bin"
        const val PENDING_UPLOAD_TEMP_SUFFIX = "$PENDING_UPLOAD_SUFFIX$TEMP_EXTENSION"
        const val PENDING_UPLOAD_MARKER_SUFFIX = "$PENDING_UPLOAD_SUFFIX$MARKER_EXTENSION"
    }
}
