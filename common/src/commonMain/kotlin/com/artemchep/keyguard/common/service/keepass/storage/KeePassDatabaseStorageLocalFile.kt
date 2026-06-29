package com.artemchep.keyguard.common.service.keepass.storage

import com.artemchep.keyguard.common.exception.KeePassFileAlreadyExistsException
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.file.FileMetadata
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.StagedDatabase
import kotlinx.io.Source

internal class KeePassDatabaseStorageLocalFile(
    private val fileService: FileService,
    private val uri: String,
    private val accessToken: FileAccessToken?,
) : KeePassDatabaseStorage {
    override suspend fun exists(): Boolean = fileService.exists(
        uri = uri,
        accessToken = accessToken,
    )

    override suspend fun stat(): KeePassDatabaseMetadata? = fileService
        .metadata(
            uri = uri,
            accessToken = accessToken,
        )
        ?.toKeePassDatabaseMetadata()

    override suspend fun read(): Source = fileService.readFromFile(
        uri = uri,
        accessToken = accessToken,
    )

    override suspend fun publish(
        mode: KeePassDatabaseWriteMode,
        staged: StagedDatabase,
        expected: KeePassDatabaseMetadata?,
    ): KeePassDatabaseMetadata? {
        if (mode == KeePassDatabaseWriteMode.Create && exists()) {
            throw KeePassFileAlreadyExistsException()
        }
        // Prefer a backend-owned atomic publish so platforms with scoped file
        // handles can keep their temp path and replacement path consistent.
        // When the backend reports that atomic publish is unsupported, fall
        // back to writing the verified bytes directly.
        val published = fileService.atomicWriteToFile(
            uri = uri,
            accessToken = accessToken,
            bytes = staged.bytes,
        )
        if (!published) {
            writeBytes(uri, staged.bytes)
        }
        return stat()
    }

    private fun writeBytes(targetUri: String, bytes: ByteArray) {
        val sink = fileService.writeToFile(
            uri = targetUri,
            accessToken = accessToken,
        )
        sink.use { it.write(bytes) }
    }
}

private fun FileMetadata.toKeePassDatabaseMetadata(): KeePassDatabaseMetadata =
    KeePassDatabaseMetadata(
        etag = null,
        lastModified = lastModified,
        size = size,
    )
