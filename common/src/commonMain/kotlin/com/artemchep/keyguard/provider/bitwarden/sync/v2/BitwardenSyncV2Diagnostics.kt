package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntitySyncPlan
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntityTypeOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncAction
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncExecutionResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncResult

class BitwardenSyncV2Diagnostics(
    private val logRepository: LogRepository?,
    private val enabled: Boolean = !isRelease,
) {
    companion object {
        private const val TAG = "SyncV2Diagnostics.bitwarden"

        val NoOp = BitwardenSyncV2Diagnostics(
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

    suspend fun entitySnapshot(
        entityName: String,
        localCount: Int,
        serverCount: Int,
    ) = debug {
        "entity_snapshot entity=$entityName local_count=$localCount server_count=$serverCount"
    }

    suspend fun entityPlanBuilt(
        entityName: String,
        plan: EntitySyncPlan<*, *>,
    ) = debug {
        val counts = plan.actions.groupingBy { actionName(it) }.eachCount()
        "entity_plan_built entity=$entityName " +
                "local_count=${plan.localSnapshot.metadata.size} " +
                "server_count=${plan.serverSnapshot.metadata.size} " +
                "total_actions=${plan.actions.size} " +
                "insert_locally=${counts[INSERT_LOCALLY].orZero()} " +
                "update_locally=${counts[UPDATE_LOCALLY].orZero()} " +
                "delete_locally=${counts[DELETE_LOCALLY].orZero()} " +
                "merge_conflict=${counts[MERGE_CONFLICT].orZero()} " +
                "delete_on_server=${counts[DELETE_ON_SERVER].orZero()} " +
                "push_to_server=${counts[PUSH_TO_SERVER].orZero()}"
    }

    suspend fun entityCompleted(
        entityName: String,
        result: SyncExecutionResult,
    ) = debug {
        "entity_completed entity=$entityName succeeded=${result.succeeded} " +
                "skipped=${result.skipped} failures=${result.failures.size} total=${result.total}"
    }

    suspend fun entityFailed(
        entityName: String,
        error: Throwable,
    ) = debug {
        "entity_failed entity=$entityName error=${error.summary()}"
    }

    suspend fun syncResult(
        result: SyncResult,
    ) = debug {
        "sync_result succeeded=${result.totalSucceeded} skipped=${result.totalSkipped} " +
                "action_failures=${result.totalActionFailures} " +
                "entity_type_failures=${result.totalEntityTypeFailures} " +
                "fully_successful=${result.isFullySuccessful}"
    }

    suspend fun executorStarted(
        entityName: String,
        actionCount: Int,
    ) = debug {
        "executor_started entity=$entityName action_count=$actionCount"
    }

    suspend fun executorPhaseStarted(
        entityName: String,
        phaseName: String,
        actionCount: Int,
    ) = debug {
        "executor_phase_started entity=$entityName phase=$phaseName action_count=$actionCount"
    }

    suspend fun executorPhaseCompleted(
        entityName: String,
        phaseName: String,
        before: SyncExecutionResult,
        after: SyncExecutionResult,
    ) = debug {
        "executor_phase_completed entity=$entityName phase=$phaseName " +
                "succeeded_delta=${after.succeeded - before.succeeded} " +
                "skipped_delta=${after.skipped - before.skipped} " +
                "failures_delta=${after.failures.size - before.failures.size} " +
                "succeeded_total=${after.succeeded} skipped_total=${after.skipped} " +
                "failures_total=${after.failures.size}"
    }

    suspend fun occSkipped(
        entityName: String,
        action: SyncAction,
        outcome: String,
        snapshotMeta: LocalItemMeta?,
    ) = debug {
        "occ_skipped entity=$entityName outcome=$outcome ${action.renderIds(snapshotMeta)}"
    }

    suspend fun localActionFailed(
        entityName: String,
        action: SyncAction,
        error: Throwable,
    ) = debug {
        "local_action_failed entity=$entityName ${action.renderIds(null)} error=${error.summary()}"
    }

    suspend fun remoteActionFailed(
        entityName: String,
        action: SyncAction,
        error: Throwable,
    ) = debug {
        "remote_action_failed entity=$entityName ${action.renderIds(null)} error=${error.summary()}"
    }

    suspend fun remoteActionSucceeded(
        entityName: String,
        action: SyncAction,
    ) = debug {
        "remote_action_succeeded entity=$entityName ${action.renderIds(null)}"
    }

    suspend fun bulkDeleteChunk(
        entityName: String,
        size: Int,
        localIds: List<String>,
        remoteIds: List<String>,
    ) = debug {
        "bulk_delete_chunk entity=$entityName size=$size local_ids=${localIds.csv()} remote_ids=${remoteIds.csv()}"
    }

    suspend fun bulkFallback(
        entityName: String,
        size: Int,
        error: Throwable,
    ) = debug {
        "bulk_fallback entity=$entityName size=$size error=${error.summary()}"
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

    private suspend fun debug(messageProvider: () -> String) {
        if (!enabled) return
        val repository = logRepository ?: return
        repository.add(
            tag = TAG,
            message = messageProvider(),
            level = LogLevel.DEBUG,
        )
    }

    private fun SyncAction.renderIds(snapshotMeta: LocalItemMeta?): String =
        when (this) {
            is SyncAction.InsertLocally ->
                "action=$INSERT_LOCALLY local_id=null remote_id=$serverId"

            is SyncAction.UpdateLocally ->
                "action=$UPDATE_LOCALLY local_id=$localId remote_id=$serverId"

            is SyncAction.DeleteLocally ->
                "action=$DELETE_LOCALLY local_id=$localId remote_id=${snapshotMeta?.remoteId}"

            is SyncAction.MergeConflict ->
                "action=$MERGE_CONFLICT local_id=$localId remote_id=$serverId"

            is SyncAction.DeleteOnServer ->
                "action=$DELETE_ON_SERVER local_id=$localId remote_id=$serverId"

            is SyncAction.PushToServer ->
                "action=$PUSH_TO_SERVER local_id=$localId remote_id=$serverId force=$force"
        }

    private fun actionName(action: SyncAction): String =
        when (action) {
            is SyncAction.InsertLocally -> INSERT_LOCALLY
            is SyncAction.UpdateLocally -> UPDATE_LOCALLY
            is SyncAction.DeleteLocally -> DELETE_LOCALLY
            is SyncAction.MergeConflict -> MERGE_CONFLICT
            is SyncAction.DeleteOnServer -> DELETE_ON_SERVER
            is SyncAction.PushToServer -> PUSH_TO_SERVER
        }

    private fun Int?.orZero() = this ?: 0

    private fun List<String>.csv() = joinToString(prefix = "[", postfix = "]")

    private fun Throwable.summary(): String {
        val name = this::class.simpleName ?: "Throwable"
        val text = this.message
        return if (text.isNullOrBlank()) {
            name
        } else {
            "$name: $text"
        }
    }
}

private const val INSERT_LOCALLY = "insert_locally"
private const val UPDATE_LOCALLY = "update_locally"
private const val DELETE_LOCALLY = "delete_locally"
private const val MERGE_CONFLICT = "merge_conflict"
private const val DELETE_ON_SERVER = "delete_on_server"
private const val PUSH_TO_SERVER = "push_to_server"
