package com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden

import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.provider.bitwarden.sync.v2.SyncDiagnostics

class BitwardenSyncDiagnostics(
    logRepository: LogRepository?,
    enabled: Boolean = !isRelease,
) : SyncDiagnostics(
    logRepository = logRepository,
    enabled = enabled,
    tag = TAG,
) {
    companion object {
        private const val TAG = "SyncDiagnostics.bitwarden"

        val NoOp = BitwardenSyncDiagnostics(
            logRepository = null,
            enabled = false,
        )
    }

    suspend fun revisionPrecheck(
        accountId: String,
        serverRevisionDate: String?,
    ) = debug {
        "revision_precheck account_id=$accountId server_revision_date=$serverRevisionDate"
    }

    suspend fun fullSyncSkipped(
        accountId: String,
        serverRevisionDate: String?,
    ) = debug {
        "full_sync_skipped account_id=$accountId server_revision_date=$serverRevisionDate"
    }

    suspend fun fullSyncStarted(
        accountId: String,
        serverRevisionDate: String?,
    ) = debug {
        "full_sync_started account_id=$accountId server_revision_date=$serverRevisionDate"
    }

    suspend fun syncResponseReceived(
        accountId: String,
        cipherCount: Int,
        folderCount: Int,
        collectionCount: Int,
        organizationCount: Int,
        sendCount: Int,
        customEquivalentDomainCount: Int,
        globalEquivalentDomainCount: Int,
    ) = debug {
        "sync_response_received account_id=$accountId " +
            "ciphers=$cipherCount folders=$folderCount collections=$collectionCount " +
            "organizations=$organizationCount sends=$sendCount " +
            "custom_equivalent_domains=$customEquivalentDomainCount " +
            "global_equivalent_domains=$globalEquivalentDomainCount"
    }

    suspend fun unknownCipherTypesSkipped(
        accountId: String,
        remoteIds: List<String>,
    ) = debug {
        "unknown_cipher_types_skipped account_id=$accountId " +
            "count=${remoteIds.size} remote_ids=${remoteIds.csv()}"
    }

    suspend fun cipherMergeStarted(
        localId: String,
        remoteId: String,
        remoteRevisionDate: Any?,
        localRevisionDate: Any?,
        localRemoteRevisionDate: Any?,
    ) = debug {
        "cipher_merge_started local_id=$localId remote_id=$remoteId " +
            "remote_revision_date=$remoteRevisionDate local_revision_date=$localRevisionDate " +
            "local_remote_revision_date=$localRemoteRevisionDate"
    }

    suspend fun cipherMergeSucceeded(
        localId: String,
        remoteId: String,
    ) = debug {
        "cipher_merge_succeeded local_id=$localId remote_id=$remoteId"
    }

    suspend fun cipherMergeFallback(
        localId: String,
        remoteId: String,
    ) = debug {
        "cipher_merge_fallback local_id=$localId remote_id=$remoteId"
    }

    suspend fun cipherAttachmentRemoteDeletionStarted(
        cipherLocalId: String,
        cipherRemoteId: String,
        attachmentRemoteId: String,
    ) = debug {
        "cipher_attachment_remote_deletion_started cipher_local_id=$cipherLocalId " +
            "cipher_remote_id=$cipherRemoteId attachment_remote_id=$attachmentRemoteId"
    }

    suspend fun cipherAttachmentRemoteDeletionCompleted(
        cipherLocalId: String,
        cipherRemoteId: String,
        attachmentRemoteId: String,
    ) = debug {
        "cipher_attachment_remote_deletion_completed cipher_local_id=$cipherLocalId " +
            "cipher_remote_id=$cipherRemoteId attachment_remote_id=$attachmentRemoteId"
    }

    suspend fun cipherAttachmentSlotRequested(
        cipherLocalId: String,
        cipherRemoteId: String,
        attachmentLocalId: String,
        requestedRemoteId: String?,
    ) = debug {
        "cipher_attachment_slot_requested cipher_local_id=$cipherLocalId cipher_remote_id=$cipherRemoteId " +
            "attachment_local_id=$attachmentLocalId requested_remote_id=$requestedRemoteId"
    }

    suspend fun cipherAttachmentSlotReserved(
        cipherLocalId: String,
        cipherRemoteId: String,
        attachmentLocalId: String,
        attachmentRemoteId: String,
    ) = debug {
        "cipher_attachment_slot_reserved cipher_local_id=$cipherLocalId cipher_remote_id=$cipherRemoteId " +
            "attachment_local_id=$attachmentLocalId attachment_remote_id=$attachmentRemoteId"
    }

    suspend fun cipherAttachmentUploadStarted(
        cipherLocalId: String,
        cipherRemoteId: String,
        attachmentLocalId: String,
        attachmentRemoteId: String,
        encryptedSize: Long,
    ) = debug {
        "cipher_attachment_upload_started cipher_local_id=$cipherLocalId cipher_remote_id=$cipherRemoteId " +
            "attachment_local_id=$attachmentLocalId attachment_remote_id=$attachmentRemoteId " +
            "encrypted_size=$encryptedSize"
    }

    suspend fun cipherAttachmentUploadCompleted(
        cipherLocalId: String,
        cipherRemoteId: String,
        attachmentLocalId: String,
        attachmentRemoteId: String,
    ) = debug {
        "cipher_attachment_upload_completed cipher_local_id=$cipherLocalId cipher_remote_id=$cipherRemoteId " +
            "attachment_local_id=$attachmentLocalId attachment_remote_id=$attachmentRemoteId"
    }

    suspend fun cipherAttachmentUploadFailed(
        cipherLocalId: String,
        cipherRemoteId: String,
        attachmentLocalId: String,
        attachmentRemoteId: String,
        cleanupSucceeded: Boolean,
        error: Throwable,
    ) = debug {
        "cipher_attachment_upload_failed cipher_local_id=$cipherLocalId cipher_remote_id=$cipherRemoteId " +
            "attachment_local_id=$attachmentLocalId attachment_remote_id=$attachmentRemoteId " +
            "cleanup_succeeded=$cleanupSucceeded error=${error.summary()}"
    }

    suspend fun cipherAttachmentMarkedUploaded(
        cipherLocalId: String,
        cipherRemoteId: String,
        attachmentLocalId: String,
        attachmentRemoteId: String,
    ) = debug {
        "cipher_attachment_marked_uploaded cipher_local_id=$cipherLocalId cipher_remote_id=$cipherRemoteId " +
            "attachment_local_id=$attachmentLocalId attachment_remote_id=$attachmentRemoteId"
    }

    suspend fun cipherAttachmentReconciled(
        cipherLocalId: String,
        cipherRemoteId: String,
        attachmentLocalId: String,
        attachmentRemoteId: String,
    ) = debug {
        "cipher_attachment_reconciled cipher_local_id=$cipherLocalId cipher_remote_id=$cipherRemoteId " +
            "attachment_local_id=$attachmentLocalId attachment_remote_id=$attachmentRemoteId"
    }

    suspend fun sendFileUploadTargetRequested(
        sendLocalId: String,
        sendRemoteId: String?,
        fileRemoteId: String?,
    ) = debug {
        "send_file_upload_target_requested send_local_id=$sendLocalId " +
            "send_remote_id=$sendRemoteId file_remote_id=$fileRemoteId"
    }

    suspend fun sendFileUploadStarted(
        sendLocalId: String,
        sendRemoteId: String?,
        fileRemoteId: String?,
        encryptedSize: Long,
        isCreate: Boolean,
    ) = debug {
        "send_file_upload_started send_local_id=$sendLocalId send_remote_id=$sendRemoteId " +
            "file_remote_id=$fileRemoteId encrypted_size=$encryptedSize is_create=$isCreate"
    }

    suspend fun sendFileUploadCompleted(
        sendLocalId: String,
        sendRemoteId: String?,
        fileRemoteId: String?,
        isCreate: Boolean,
    ) = debug {
        "send_file_upload_completed send_local_id=$sendLocalId send_remote_id=$sendRemoteId " +
            "file_remote_id=$fileRemoteId is_create=$isCreate"
    }

    suspend fun sendFileUploadFailed(
        sendLocalId: String,
        sendRemoteId: String?,
        fileRemoteId: String?,
        isCreate: Boolean,
        cleanupSucceeded: Boolean?,
        error: Throwable,
    ) = debug {
        "send_file_upload_failed send_local_id=$sendLocalId send_remote_id=$sendRemoteId " +
            "file_remote_id=$fileRemoteId is_create=$isCreate cleanup_succeeded=$cleanupSucceeded " +
            "error=${error.summary()}"
    }

    suspend fun sendFileMarkedUploaded(
        sendLocalId: String,
        sendRemoteId: String?,
        fileRemoteId: String?,
    ) = debug {
        "send_file_marked_uploaded send_local_id=$sendLocalId send_remote_id=$sendRemoteId " +
            "file_remote_id=$fileRemoteId"
    }

    suspend fun sendFileReconciled(
        sendLocalId: String,
        sendRemoteId: String?,
        fileRemoteId: String?,
        uploadCompletedLocally: Boolean,
    ) = debug {
        "send_file_reconciled send_local_id=$sendLocalId send_remote_id=$sendRemoteId " +
            "file_remote_id=$fileRemoteId upload_completed_locally=$uploadCompletedLocally"
    }
}
