package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntitySyncPlan
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncAction
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncExecutionResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncResult

private const val INSERT_LOCALLY = "insert_locally"
private const val UPDATE_LOCALLY = "update_locally"
private const val DELETE_LOCALLY = "delete_locally"
private const val MERGE_CONFLICT = "merge_conflict"
private const val DELETE_ON_SERVER = "delete_on_server"
private const val PUSH_TO_SERVER = "push_to_server"

open class SyncDiagnostics(
    private val logRepository: LogRepository?,
    val enabled: Boolean = !isRelease,
    private val tag: String = TAG,
) {
    companion object {
        private const val TAG = "SyncDiagnostics"

        val NoOp = SyncDiagnostics(
            logRepository = null,
            enabled = false,
        )
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

    protected suspend fun debug(messageProvider: () -> String) =
        log(LogLevel.DEBUG, messageProvider)

    protected suspend fun info(messageProvider: () -> String) =
        log(LogLevel.INFO, messageProvider)

    protected suspend fun warning(messageProvider: () -> String) =
        log(LogLevel.WARNING, messageProvider)

    private suspend fun log(
        level: LogLevel,
        messageProvider: () -> String,
    ) {
        if (!enabled) return
        val repository = logRepository ?: return
        repository.add(
            tag = tag,
            message = messageProvider(),
            level = level,
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

    protected fun List<String>.csv() = joinToString(prefix = "[", postfix = "]")

    protected fun Throwable.summary(): String {
        val name = this::class.simpleName
            ?: "Throwable"
        val text = this.message
        return if (text.isNullOrBlank()) {
            name
        } else {
            "$name: $text"
        }
    }
}
