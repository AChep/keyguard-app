package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.Password
import kotlin.time.Instant
import kotlinx.io.Sink

interface BackupRepository {
    suspend fun getOrCreateMetadata(
        store: BackupObjectStore,
        password: Password?,
        nowProvider: () -> Instant,
        repoIdProvider: () -> String,
    ): BackupRepositoryMetadata

    /**
     * Returns the newest generation that has at least one readable index.
     * Multiple readable indexes in that generation are returned for merge.
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
