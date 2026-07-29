package com.artemchep.keyguard.common.service.keepass.storage

import com.artemchep.keyguard.common.exception.KeePassFileAlreadyExistsException
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.file.AtomicFileWriteOutcome
import com.artemchep.keyguard.common.service.file.FileMetadata
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.StagedDatabase
import kotlinx.io.Source
import kotlinx.io.readByteArray

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
        val outcome = fileService.atomicWriteToFile(
            uri = uri,
            accessToken = accessToken,
            write = staged::replayTo,
        )
        if (outcome is AtomicFileWriteOutcome.Published) {
            outcome.receipt.requireCleanupComplete()
        }
        if (outcome == AtomicFileWriteOutcome.Unsupported) {
            // The direct-write fallback truncates the destination on open, so
            // materialize the staged payload first: a staging read failure
            // must not be able to tear the database once the destination has
            // been opened.
            val bytes = staged.source().use { source ->
                source.readByteArray()
            }
            try {
                fileService.writeToFile(
                    uri = uri,
                    accessToken = accessToken,
                ).use { sink ->
                    sink.write(bytes)
                }
            } finally {
                bytes.fill(0)
            }
        }
        return stat()
    }
}

private fun FileMetadata.toKeePassDatabaseMetadata(): KeePassDatabaseMetadata =
    KeePassDatabaseMetadata(
        etag = null,
        lastModified = lastModified,
        size = size,
    )
