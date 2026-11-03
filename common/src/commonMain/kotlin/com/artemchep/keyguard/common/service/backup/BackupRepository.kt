package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.Password
import kotlin.time.Instant
import kotlinx.io.Sink
import kotlinx.io.Source

@Suppress("TooManyFunctions") // One per object operation, for the runner and the import.
interface BackupRepository {
    suspend fun getOrCreateMetadata(
        store: BackupObjectStore,
        password: Password?,
        nowProvider: () -> Instant,
        repoIdProvider: () -> String,
    ): BackupRepositoryMetadata

    /**
     * Returns the metadata of the repository in [store], or `null` when it
     * holds none. Unlike [getOrCreateMetadata], never writes.
     *
     * Throws `ZipPasswordException` when the metadata is encrypted and
     * [password] is missing or wrong, `ZipEncryptionRequiredException` when
     * the metadata is not encrypted but a [password] is given, and
     * [IllegalStateException] when this version does not support the
     * repository's format.
     */
    suspend fun readMetadata(
        store: BackupObjectStore,
        password: Password?,
    ): BackupRepositoryMetadata?

    /**
     * Returns the newest generation that has at least one readable index.
     * Multiple readable indexes in that generation are returned for merge.
     *
     * With a [password], throws instead of falling back when an index is not
     * AES-encrypted, or when indexes exist but none of them can be read.
     */
    suspend fun readIndexes(
        store: BackupObjectStore,
        password: Password?,
    ): List<BackupIndex>

    suspend fun writeIndex(
        store: BackupObjectStore,
        password: Password?,
        index: BackupIndex,
    )

    suspend fun hasBlob(
        store: BackupObjectStore,
        blobPath: String,
    ): Boolean

    suspend fun validateBlob(
        store: BackupObjectStore,
        objectPassword: Password?,
        blobPath: String,
    ): BackupBlobValidationResult

    suspend fun writeBlob(
        store: BackupObjectStore,
        objectPassword: Password?,
        blobPath: String,
        write: suspend (Sink) -> Unit,
    ): Long

    suspend fun writeSnapshot(
        store: BackupObjectStore,
        objectPassword: Password?,
        snapshotId: String,
        manifest: BackupSnapshotManifest,
        vaultJson: String,
    )

    suspend fun listSnapshotIds(
        store: BackupObjectStore,
    ): List<String>

    suspend fun readSnapshotManifest(
        store: BackupObjectStore,
        objectPassword: Password?,
        snapshotId: String,
    ): BackupSnapshotManifest?

    /**
     * Reads the snapshot archive at [snapshotPath], the path an index lists
     * it under: its manifest, then its vault JSON streamed to [readVault].
     * Returns `null` when the archive or either entry is missing.
     *
     * A transient store error reopens the archive and calls [readVault]
     * again, so it must not keep state between calls.
     *
     * [readVault] must not close the source. What it leaves unread is read
     * afterwards, as only an entry read to its end is proven intact.
     */
    suspend fun <T> readSnapshot(
        store: BackupObjectStore,
        objectPassword: Password?,
        snapshotPath: String,
        readVault: suspend (BackupSnapshotManifest, Source) -> T,
    ): T?

    /**
     * Streams the attachment of the blob at [blobPath] to [read]. Returns
     * `null` when the blob is missing. Retried and read to its end like
     * [readSnapshot].
     */
    suspend fun <T> readBlob(
        store: BackupObjectStore,
        objectPassword: Password?,
        blobPath: String,
        read: suspend (Source) -> T,
    ): T?

    suspend fun deleteSnapshot(
        store: BackupObjectStore,
        snapshotId: String,
    )

    suspend fun deleteBlob(
        store: BackupObjectStore,
        blobPath: String,
    )
}

sealed interface BackupBlobValidationResult {
    data object Valid : BackupBlobValidationResult
    data object Invalid : BackupBlobValidationResult
    data object Unavailable : BackupBlobValidationResult
}
