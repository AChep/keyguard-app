package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.Password
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BackupConfig(
    val enabled: Boolean = false,
    val store: BackupStoreConfig = BackupStoreConfig.Local(),
    val password: Password? = null,
    val includeAttachments: Boolean = true,
    val filter: DFilter = DFilter.All,
    val intervalMs: Long = 24.hours.inWholeMilliseconds,
    val retention: BackupRetention = BackupRetention(),
) {
    fun canRun(): Boolean = enabled && store.isConfigured

    fun requiresNetwork(): Boolean = includeAttachments ||
            store.requiresNetwork
}

@Serializable
sealed interface BackupStoreConfig {
    val isConfigured: Boolean
    val requiresNetwork: Boolean

    @Serializable
    @SerialName("local")
    data class Local(
        val path: String? = null,
    ) : BackupStoreConfig {
        override val isConfigured: Boolean
            get() = !path.isNullOrBlank()

        override val requiresNetwork: Boolean
            get() = false
    }

    @Serializable
    @SerialName("web_dav")
    data class WebDav(
        val url: String? = null,
        val username: String? = null,
        val password: Password? = null,
    ) : BackupStoreConfig {
        override val isConfigured: Boolean
            get() = !url.isNullOrBlank()

        override val requiresNetwork: Boolean
            get() = true
    }
}

@Serializable
data class BackupRetention(
    /**
     * A value of [NEVER_CLEAR_MAX_SNAPSHOTS] disables pruning.
     */
    val maxSnapshots: Int = 30,
) {
    companion object {
        const val NEVER_CLEAR_MAX_SNAPSHOTS = 0
        const val MAX_SNAPSHOTS_LIMIT = 365
    }
}

@Serializable
data class BackupRepositoryMetadata(
    val formatVersion: Int = 1,
    val repoId: String,
    val createdAt: Instant,
    val app: String = "keyguard",
    val features: List<String> = listOf(
        "encrypted-zip-metadata",
        "generational-index-zips",
        "authoritative-index",
        "index-snapshot-catalog",
        "index-object-keys",
        "snapshot-vault-json",
        "attachment-blob-zips",
        "random-blob-ids",
    ),
    val crypto: BackupRepositoryCrypto = BackupRepositoryCrypto(),
    val layout: BackupRepositoryLayout = BackupRepositoryLayout(),
)

@Serializable
data class BackupRepositoryCrypto(
    val archive: String = "zip-aes-256",
    val objectArchive: String = "zip-aes-256",
    val attachmentFingerprint: String = "hmac-sha256",
)

@Serializable
data class BackupRepositoryLayout(
    val repo: String = "repo.zip",
    val indexes: String = "indexes/",
    val snapshots: String = "snapshots/",
    val blobs: String = "blobs/",
)

@Serializable
data class BackupIndex(
    val formatVersion: Int = 1,
    val indexId: String = "",
    val generation: Long = 0L,
    val parentIndexIds: Set<String> = emptySet(),
    val updatedAt: Instant? = null,
    val snapshots: Map<String, BackupIndexSnapshot> = emptyMap(),
    val attachments: Map<String, BackupIndexAttachment> = emptyMap(),
    val blobs: Map<String, BackupIndexBlob> = emptyMap(),
)

@Serializable
data class BackupIndexSnapshot(
    val path: String,
    val createdAt: Instant,
    val vaultSize: Long,
    val blobIds: Set<String> = emptySet(),
    val encryption: BackupObjectEncryption = BackupObjectEncryption.None,
    val stats: BackupSnapshotStats,
)

@Serializable
data class BackupIndexAttachment(
    val blobId: String,
    val plainSize: Long?,
    val createdAt: Instant,
    val lastSeenAt: Instant,
)

@Serializable
data class BackupIndexBlob(
    val path: String,
    val plainSize: Long?,
    val encryptedSize: Long?,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val lastValidatedAt: Instant? = null,
    val encryption: BackupObjectEncryption = BackupObjectEncryption.None,
)

@Serializable
data class BackupObjectEncryption(
    val method: BackupObjectEncryptionMethod = BackupObjectEncryptionMethod.None,
    val keyBase64: String? = null,
) {
    init {
        when (method) {
            BackupObjectEncryptionMethod.None -> require(keyBase64 == null) {
                "Unencrypted backup objects must not have an encryption key."
            }

            BackupObjectEncryptionMethod.ZipAes256 -> require(!keyBase64.isNullOrBlank()) {
                "Encrypted backup objects must have an encryption key."
            }
        }
    }

    companion object {
        val None = BackupObjectEncryption()
    }
}

@Serializable
enum class BackupObjectEncryptionMethod {
    @SerialName("none")
    None,

    @SerialName("zip_aes_256")
    ZipAes256,
}

@Serializable
data class BackupSnapshotManifest(
    val formatVersion: Int = 1,
    val snapshotId: String,
    val createdAt: Instant,
    val options: BackupSnapshotOptions,
    val vault: BackupSnapshotVault,
    val attachments: List<BackupSnapshotAttachment>,
    val stats: BackupSnapshotStats,
)

@Serializable
data class BackupSnapshotOptions(
    val includeAttachments: Boolean,
)

@Serializable
data class BackupSnapshotVault(
    val entry: String = "vault.json",
    val size: Long,
)

@Serializable
data class BackupSnapshotAttachment(
    val accountId: String,
    val localCipherId: String,
    val remoteCipherId: String?,
    val attachmentId: String,
    val fileName: String,
    val plainSize: Long?,
    val fingerprint: String,
    val blobId: String,
    val blobPath: String,
    val exportPath: String,
)

@Serializable
data class BackupSnapshotStats(
    val cipherCount: Int,
    val attachmentCount: Int,
    val skippedAttachmentCount: Int = 0,
    val newBlobCount: Int,
    val reusedBlobCount: Int,
)

@Serializable
data class BackupStatus(
    val lastStartedAt: Instant? = null,
    val lastFinishedAt: Instant? = null,
    val lastSnapshotId: String? = null,
    val lastSkippedReason: String? = null,
    val lastErrorMessage: String? = null,
    val lastStats: BackupSnapshotStats? = null,
    val changeGeneration: Long = 0L,
    val lastChangedAt: Instant? = null,
    val lastSuccessfulBackupAt: Instant? = null,
    val lastSuccessfulBackupChangeGeneration: Long = 0L,
    val currentRun: BackupRunProgress? = null,
) {
    val isDirty: Boolean
        get() = changeGeneration > lastSuccessfulBackupChangeGeneration
}

data class BackupRunResult(
    val snapshotId: String?,
    val skipped: Boolean,
    val reason: String? = null,
    val stats: BackupSnapshotStats? = null,
)

@Serializable
data class BackupRunProgress(
    val runId: String,
    val trigger: String,
    val startedAt: Instant,
    val step: BackupStep,
    val details: BackupRunProgressDetails = BackupRunProgressDetails(),
)

@Serializable
enum class BackupStep {
    @SerialName("preparing")
    Preparing,

    @SerialName("opening_repository")
    OpeningRepository,

    @SerialName("exporting_vault")
    ExportingVault,

    @SerialName("scanning_attachments")
    ScanningAttachments,

    @SerialName("backing_up_attachments")
    BackingUpAttachments,

    @SerialName("writing_index")
    WritingIndex,

    @SerialName("writing_snapshot")
    WritingSnapshot,

    @SerialName("applying_retention")
    ApplyingRetention,
}

@Serializable
data class BackupRunProgressDetails(
    val itemsProcessed: Int? = null,
    val itemsTotal: Int? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
)

fun interface BackupProgressReporter {
    companion object {
        val NoOp = BackupProgressReporter {
            // no-op
        }
    }

    suspend fun report(
        progress: BackupRunProgress,
    )
}

data class BackupRunProgressContext(
    val runId: String,
    val trigger: String,
    val startedAt: Instant,
    val reporter: BackupProgressReporter = BackupProgressReporter.NoOp,
) {
    suspend fun report(
        step: BackupStep,
        details: BackupRunProgressDetails = BackupRunProgressDetails(),
    ) {
        reporter.report(
            BackupRunProgress(
                runId = runId,
                trigger = trigger,
                startedAt = startedAt,
                step = step,
                details = details,
            ),
        )
    }
}

fun createBackupRunId(
    trigger: String,
    startedAt: Instant,
): String = "$trigger-${startedAt.toEpochMilliseconds()}"
