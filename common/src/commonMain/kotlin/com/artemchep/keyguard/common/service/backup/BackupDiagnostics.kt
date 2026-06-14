package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.platform.util.isRelease

class BackupDiagnostics(
    private val logRepository: LogRepository?,
    private val enabled: Boolean = !isRelease,
) {
    companion object {
        private const val TAG = "BackupDiagnostics"

        val NoOp = BackupDiagnostics(
            logRepository = null,
            enabled = false,
        )
    }

    suspend fun backupRequestStarted(
        trigger: String,
        includeAttachments: Boolean,
        retentionMaxSnapshots: Int,
    ) = debug {
        "backup_request_started trigger=$trigger include_attachments=$includeAttachments " +
                "retention_max_snapshots=$retentionMaxSnapshots"
    }

    suspend fun backupSkipped(
        trigger: String,
        reason: String,
    ) = debug {
        "backup_skipped trigger=$trigger reason=$reason"
    }

    suspend fun backupRequestCompleted(
        trigger: String,
        result: BackupRunResult,
    ) = debug {
        "backup_request_completed trigger=$trigger snapshot_id=${result.snapshotId} " +
                "skipped=${result.skipped} reason=${result.reason} ${result.stats.renderStats()}"
    }

    suspend fun backupRequestFailed(
        trigger: String,
        error: Throwable,
    ) = debug {
        "backup_request_failed trigger=$trigger error=${error.summary()}"
    }

    suspend fun backupRunStarted(
        includeAttachments: Boolean,
        retentionMaxSnapshots: Int,
    ) = debug {
        "backup_run_started include_attachments=$includeAttachments " +
                "retention_max_snapshots=$retentionMaxSnapshots"
    }

    suspend fun backupRepositoryReady(
        formatVersion: Int,
        featureCount: Int,
    ) = debug {
        "backup_repository_ready format_version=$formatVersion feature_count=$featureCount"
    }

    suspend fun backupIndexLoaded(
        generation: Long,
        attachmentCount: Int,
        blobCount: Int,
    ) = debug {
        "backup_index_loaded generation=$generation attachment_count=$attachmentCount " +
                "blob_count=$blobCount"
    }

    suspend fun backupExportCreated(
        cipherCount: Int,
        folderCount: Int,
        collectionCount: Int,
        organizationCount: Int,
        vaultSize: Long,
    ) = debug {
        "backup_export_created cipher_count=$cipherCount folder_count=$folderCount " +
                "collection_count=$collectionCount organization_count=$organizationCount " +
                "vault_size=$vaultSize"
    }

    suspend fun backupIndexWritten(
        generation: Long,
        attachmentCount: Int,
        blobCount: Int,
    ) = debug {
        "backup_index_written generation=$generation attachment_count=$attachmentCount " +
                "blob_count=$blobCount"
    }

    suspend fun backupSnapshotWritten(
        snapshotId: String,
        vaultSize: Long,
        attachmentCount: Int,
    ) = debug {
        "backup_snapshot_written snapshot_id=$snapshotId vault_size=$vaultSize " +
                "attachment_count=$attachmentCount"
    }

    suspend fun backupRunCompleted(
        result: BackupRunResult,
    ) = debug {
        "backup_run_completed snapshot_id=${result.snapshotId} skipped=${result.skipped} " +
                "reason=${result.reason} ${result.stats.renderStats()}"
    }

    suspend fun backupRunFailed(
        error: Throwable,
    ) = debug {
        "backup_run_failed error=${error.summary()}"
    }

    suspend fun backupAttachmentsStarted(
        cipherCount: Int,
        indexedAttachmentCount: Int,
        indexedBlobCount: Int,
    ) = debug {
        "backup_attachments_started cipher_count=$cipherCount " +
                "indexed_attachment_count=$indexedAttachmentCount indexed_blob_count=$indexedBlobCount"
    }

    suspend fun backupAttachmentSkipped(
        localCipherId: String,
        attachmentId: String,
        reason: String,
    ) = debug {
        "backup_attachment_skipped local_cipher_id=$localCipherId attachment_id=$attachmentId " +
                "reason=$reason"
    }

    suspend fun backupAttachmentBlobReused(
        localCipherId: String,
        remoteCipherId: String,
        attachmentId: String,
        plainSize: Long?,
        encryptedSize: Long?,
    ) = debug {
        "backup_attachment_blob_reused local_cipher_id=$localCipherId " +
                "remote_cipher_id=$remoteCipherId attachment_id=$attachmentId " +
                "plain_size=$plainSize encrypted_size=$encryptedSize"
    }

    suspend fun backupAttachmentDownloadStarted(
        localCipherId: String,
        remoteCipherId: String,
        attachmentId: String,
        attachmentName: String,
        sourceType: String?,
        plainSize: Long?,
    ) = debug {
        "backup_attachment_download_started local_cipher_id=$localCipherId " +
                "remote_cipher_id=$remoteCipherId attachment_id=$attachmentId " +
                "attachment_name=$attachmentName source_type=$sourceType plain_size=$plainSize"
    }

    suspend fun backupAttachmentDownloadCompleted(
        localCipherId: String,
        remoteCipherId: String,
        attachmentId: String,
        sourceType: String?,
        plainSize: Long?,
    ) = debug {
        "backup_attachment_download_completed local_cipher_id=$localCipherId " +
                "remote_cipher_id=$remoteCipherId attachment_id=$attachmentId " +
                "source_type=$sourceType plain_size=$plainSize"
    }

    suspend fun backupAttachmentDownloadFailed(
        localCipherId: String,
        remoteCipherId: String,
        attachmentId: String,
        sourceType: String?,
        error: Throwable,
    ) = debug {
        "backup_attachment_download_failed local_cipher_id=$localCipherId " +
                "remote_cipher_id=$remoteCipherId attachment_id=$attachmentId " +
                "source_type=$sourceType error=${error.summary()}"
    }

    suspend fun backupAttachmentsCompleted(
        attachmentCount: Int,
        newBlobCount: Int,
        reusedBlobCount: Int,
        skippedAttachmentCount: Int,
    ) = debug {
        "backup_attachments_completed attachment_count=$attachmentCount " +
                "new_blob_count=$newBlobCount reused_blob_count=$reusedBlobCount " +
                "skipped_attachment_count=$skippedAttachmentCount"
    }

    suspend fun backupRetentionStarted(
        maxSnapshots: Int,
        snapshotCount: Int,
    ) = debug {
        "backup_retention_started max_snapshots=$maxSnapshots snapshot_count=$snapshotCount"
    }

    suspend fun backupRetentionCompleted(
        maxSnapshots: Int,
        retainedSnapshotCount: Int,
        deletedSnapshotCount: Int,
        deletedBlobCount: Int,
        indexUpdated: Boolean,
    ) = debug {
        "backup_retention_completed max_snapshots=$maxSnapshots " +
                "retained_snapshot_count=$retainedSnapshotCount " +
                "deleted_snapshot_count=$deletedSnapshotCount deleted_blob_count=$deletedBlobCount " +
                "index_updated=$indexUpdated"
    }

    private suspend fun debug(messageProvider: () -> String) {
        if (!enabled) return
        val repository = logRepository ?: return
        repository.add(
            tag = TAG,
            message = messageProvider(),
            level = LogLevel.DEBUG,
        )
    }

    private fun BackupSnapshotStats?.renderStats(): String =
        if (this == null) {
            "cipher_count=null attachment_count=null skipped_attachment_count=null " +
                    "new_blob_count=null reused_blob_count=null"
        } else {
            "cipher_count=$cipherCount attachment_count=$attachmentCount " +
                    "skipped_attachment_count=$skippedAttachmentCount " +
                    "new_blob_count=$newBlobCount reused_blob_count=$reusedBlobCount"
        }

    private fun Throwable.summary(): String {
        val name = this::class.simpleName ?: "Throwable"
        val text = message
            ?.takeIf { it.isNotBlank() }
            ?.redactSensitiveDiagnosticText()
        return if (text.isNullOrBlank()) {
            name
        } else {
            "$name: $text"
        }
    }

    private fun String.redactSensitiveDiagnosticText(): String = this
        .replace(URL_REGEX, "<redacted-url>")
        .replace(UNIX_PATH_REGEX) { match ->
            match.groupValues[1] + "<redacted-path>"
        }
        .replace(WINDOWS_PATH_REGEX, "<redacted-path>")
        .replace(FILE_NAME_REGEX, "<redacted-file>")
        .take(MAX_ERROR_MESSAGE_LENGTH)
}

private const val MAX_ERROR_MESSAGE_LENGTH = 200

private val URL_REGEX = Regex("""\b(?:https?|file)://\S+""")
private val UNIX_PATH_REGEX = Regex("""(^|[^\w./-])/(?:[^\s:]+/)*[^\s:]+""")
private val WINDOWS_PATH_REGEX = Regex("""\b[A-Za-z]:\\[^\s]+""")
private val FILE_NAME_REGEX = Regex("""\b(?=[\w.-]*[A-Za-z])[\w.-]+\.[A-Za-z0-9]{1,12}\b""")
