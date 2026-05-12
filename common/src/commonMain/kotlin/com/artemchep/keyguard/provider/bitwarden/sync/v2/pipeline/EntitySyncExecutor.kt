package com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.sync.v2.BitwardenSyncV2Diagnostics
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ActionFailure
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntitySyncPlan
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncAction
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncExecutionResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.EntitySyncStrategy
import com.artemchep.keyguard.provider.bitwarden.sync.v2.throwIfCancellation
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Optional bulk server operations for entity types that support
 * batched API calls (e.g. ciphers).
 *
 * When provided to [EntitySyncExecutor], delete/restore/trash
 * operations are batched in chunks of [EntitySyncExecutor.BULK_CHUNK_SIZE].
 * On any failure the executor falls back to individual per-item calls.
 */
interface BulkRemoteOps<Local : BitwardenService.Has<Local>> {
    /** Deletes entities on the server in bulk (soft or hard delete). */
    suspend fun bulkDeleteOnServer(entries: List<Pair<Local, String>>)

    /** Restores previously trashed entities on the server in bulk. */
    suspend fun bulkRestoreOnServer(serverIds: List<String>)

    /** Moves entities to the server trash in bulk. */
    suspend fun bulkTrashOnServer(serverIds: List<String>)
}

/**
 * Executes the actions in an [EntitySyncPlan] using optimistic
 * concurrency control (OCC).
 *
 * **Execution phases** (order matters for correctness):
 * 1. **DeleteLocally** — remove entities the server no longer has.
 * 2. **InsertLocally** — add new server entities to the local DB.
 * 3. **UpdateLocally** — overwrite local entities with newer server data.
 * 4. **MergeConflict** — three-way merge for entities changed on both sides.
 * 5. **DeleteOnServer** — remove locally-deleted entities from the server.
 * 6. **PushToServer** — upload locally-modified entities to the server.
 *
 * **OCC**: Before every action that touches an entity, the executor
 * re-reads it from the database and compares its [LocalItemMeta] to
 * the snapshot taken at plan-build time. If the metadata differs
 * (user edited the entity while sync was in progress), the action is
 * skipped to avoid overwriting concurrent changes.
 *
 * **Bulk operations**: When [bulkRemoteOps] is non-null, Phase 5
 * batches server deletes via [BulkRemoteOps.bulkDeleteOnServer].
 * On any failure the executor falls back to individual calls.
 *
 * **Cancellation**: [ensureActive] is called between every phase
 * and between batch chunks.
 *
 * @param strategy metadata extractor for OCC comparison.
 * @param ops entity-specific DB/API/crypto operations.
 * @param bulkRemoteOps optional bulk server operations; when non-null,
 *   the executor batches Phase 5 calls.
 */
class EntitySyncExecutor<Local : BitwardenService.Has<Local>, Server : Any>(
    private val strategy: EntitySyncStrategy<Local, Server>,
    private val ops: EntitySyncOps<Local, Server>,
    private val bulkRemoteOps: BulkRemoteOps<Local>? = null,
    private val entityName: String = "unknown",
    private val diagnostics: BitwardenSyncV2Diagnostics = BitwardenSyncV2Diagnostics.NoOp,
) {
    companion object {
        /** Chunk size for local database batch writes. */
        const val BATCH_SIZE = 200

        /** Chunk size for bulk server API calls. */
        const val BULK_CHUNK_SIZE = 500
    }

    /**
     * Result of an OCC check against the entity's current DB state.
     *
     * - [SAFE]: metadata unchanged since snapshot — proceed.
     * - [MODIFIED]: user edited the entity concurrently — skip.
     * - [DELETED]: entity was removed from the DB — skip.
     */
    private enum class ConcurrencyOutcome {
        SAFE,
        MODIFIED,
        DELETED,
    }

    private class ConcurrencyCheckResult<out L>(
        val outcome: ConcurrencyOutcome,
        val currentEntity: L?,
    )

    /** Executes all actions from the [plan] and returns an aggregate result. */
    suspend fun execute(plan: EntitySyncPlan<Local, Server>): SyncExecutionResult {
        diagnostics.executorStarted(
            entityName = entityName,
            actionCount = plan.actions.size,
        )
        val snapshotMetaByLocalId =
            plan.localSnapshot.metadata
                .associateBy { it.localId }

        val tracker = ResultTracker(
            staleServerEntities = plan.staleServerEntities,
        )

        val deleteLocallyActions = mutableListOf<SyncAction.DeleteLocally>()
        val insertLocallyActions = mutableListOf<SyncAction.InsertLocally>()
        val updateLocallyActions = mutableListOf<SyncAction.UpdateLocally>()
        val mergeConflictActions = mutableListOf<SyncAction.MergeConflict>()
        val deleteOnServerActions = mutableListOf<SyncAction.DeleteOnServer>()
        val pushToServerActions = mutableListOf<SyncAction.PushToServer>()

        for (action in plan.actions) {
            when (action) {
                is SyncAction.DeleteLocally -> deleteLocallyActions.add(action)
                is SyncAction.InsertLocally -> insertLocallyActions.add(action)
                is SyncAction.UpdateLocally -> updateLocallyActions.add(action)
                is SyncAction.MergeConflict -> mergeConflictActions.add(action)
                is SyncAction.DeleteOnServer -> deleteOnServerActions.add(action)
                is SyncAction.PushToServer -> pushToServerActions.add(action)
            }
        }

        // --- Phase 1: Delete locally ---
        phaseStarted(
            phaseName = PHASE_DELETE_LOCALLY,
            actionCount = deleteLocallyActions.size,
            tracker = tracker,
        )
        executeDeleteLocally(
            actions = deleteLocallyActions,
            snapshotMetaByLocalId = snapshotMetaByLocalId,
            tracker = tracker,
        )
        phaseCompleted(
            phaseName = PHASE_DELETE_LOCALLY,
            before = tracker.lastPhaseSnapshot,
            tracker = tracker,
        )
        coroutineContext.ensureActive()

        // --- Phase 2a: Insert locally (no concurrency check) ---
        phaseStarted(
            phaseName = PHASE_INSERT_LOCALLY,
            actionCount = insertLocallyActions.size,
            tracker = tracker,
        )
        executeInsertLocally(
            actions = insertLocallyActions,
            plan = plan,
            tracker = tracker,
        )
        phaseCompleted(
            phaseName = PHASE_INSERT_LOCALLY,
            before = tracker.lastPhaseSnapshot,
            tracker = tracker,
        )
        coroutineContext.ensureActive()

        // --- Phase 2b: Update locally (with concurrency check) ---
        phaseStarted(
            phaseName = PHASE_UPDATE_LOCALLY,
            actionCount = updateLocallyActions.size,
            tracker = tracker,
        )
        executeUpdateLocally(
            actions = updateLocallyActions,
            plan = plan,
            snapshotMetaByLocalId = snapshotMetaByLocalId,
            tracker = tracker,
        )
        phaseCompleted(
            phaseName = PHASE_UPDATE_LOCALLY,
            before = tracker.lastPhaseSnapshot,
            tracker = tracker,
        )
        coroutineContext.ensureActive()

        // --- Phase 3: Merge conflicts ---
        phaseStarted(
            phaseName = PHASE_MERGE_CONFLICT,
            actionCount = mergeConflictActions.size,
            tracker = tracker,
        )
        for (action in mergeConflictActions) {
            coroutineContext.ensureActive()
            executeRemoteWithFinalization(
                action = action,
                localId = action.localId,
                snapshotMeta = snapshotMetaByLocalId[action.localId],
                tracker = tracker,
            ) { currentEntity ->
                val serverEntity =
                    plan.serverSnapshot.entitiesById[action.serverId]
                        ?: error("Server entity ${action.serverId} not found in snapshot")
                ops.mergeConflict(currentEntity, serverEntity)
            }
        }
        phaseCompleted(
            phaseName = PHASE_MERGE_CONFLICT,
            before = tracker.lastPhaseSnapshot,
            tracker = tracker,
        )
        coroutineContext.ensureActive()

        // --- Phase 4: Delete on server ---
        val deleteOnServerPhase =
            if (bulkRemoteOps != null && deleteOnServerActions.isNotEmpty()) {
                PHASE_DELETE_ON_SERVER_BULK
            } else {
                PHASE_DELETE_ON_SERVER
            }
        phaseStarted(
            phaseName = deleteOnServerPhase,
            actionCount = deleteOnServerActions.size,
            tracker = tracker,
        )
        if (bulkRemoteOps != null && deleteOnServerActions.isNotEmpty()) {
            executeBulkDeleteOnServer(
                actions = deleteOnServerActions,
                snapshotMetaByLocalId = snapshotMetaByLocalId,
                tracker = tracker,
            )
        } else {
            for (action in deleteOnServerActions) {
                coroutineContext.ensureActive()
                executeRemoteWithFinalization(
                    action = action,
                    localId = action.localId,
                    snapshotMeta = snapshotMetaByLocalId[action.localId],
                    tracker = tracker,
                ) { currentEntity ->
                    ops.deleteOnServer(currentEntity, action.serverId)
                }
            }
        }
        phaseCompleted(
            phaseName = deleteOnServerPhase,
            before = tracker.lastPhaseSnapshot,
            tracker = tracker,
        )
        coroutineContext.ensureActive()

        // --- Phase 5: Push to server ---
        phaseStarted(
            phaseName = PHASE_PUSH_TO_SERVER,
            actionCount = pushToServerActions.size,
            tracker = tracker,
        )
        for (action in pushToServerActions) {
            coroutineContext.ensureActive()
            executeRemoteWithFinalization(
                action = action,
                localId = action.localId,
                snapshotMeta = snapshotMetaByLocalId[action.localId],
                tracker = tracker,
            ) { currentEntity ->
                val serverEntity =
                    action.serverId
                        ?.let { plan.serverSnapshot.entitiesById[it] }
                ops.pushToServer(currentEntity, serverEntity, action.force)
            }
        }
        phaseCompleted(
            phaseName = PHASE_PUSH_TO_SERVER,
            before = tracker.lastPhaseSnapshot,
            tracker = tracker,
        )

        return tracker.toResult()
    }

    private suspend fun phaseStarted(
        phaseName: String,
        actionCount: Int,
        tracker: ResultTracker,
    ) {
        tracker.lastPhaseSnapshot = tracker.toResult()
        diagnostics.executorPhaseStarted(
            entityName = entityName,
            phaseName = phaseName,
            actionCount = actionCount,
        )
    }

    private suspend fun phaseCompleted(
        phaseName: String,
        before: SyncExecutionResult,
        tracker: ResultTracker,
    ) {
        diagnostics.executorPhaseCompleted(
            entityName = entityName,
            phaseName = phaseName,
            before = before,
            after = tracker.toResult(),
        )
    }

    // ---------------------------------------------------------------
    // Phase implementations
    // ---------------------------------------------------------------

    /**
     * Batch-deletes local entities, with OCC check before each chunk.
     * Already-deleted entities are counted as successes.
     */
    private suspend fun executeDeleteLocally(
        actions: List<SyncAction.DeleteLocally>,
        snapshotMetaByLocalId: Map<String, LocalItemMeta>,
        tracker: ResultTracker,
    ) {
        if (actions.isEmpty()) return

        for (chunk in actions.chunked(BATCH_SIZE)) {
            coroutineContext.ensureActive()
            val finalSafeActions = mutableListOf<SyncAction.DeleteLocally>()
            for (action in chunk) {
                val snapshotMeta = snapshotMetaByLocalId[action.localId]
                val result = checkConcurrency(action.localId, snapshotMeta)
                when (result.outcome) {
                    ConcurrencyOutcome.SAFE -> finalSafeActions.add(action)
                    ConcurrencyOutcome.MODIFIED -> {
                        diagnostics.occSkipped(
                            entityName = entityName,
                            action = action,
                            outcome = OCC_MODIFIED,
                            snapshotMeta = snapshotMeta,
                        )
                        tracker.skipped++
                    }
                    ConcurrencyOutcome.DELETED -> tracker.succeeded++
                }
            }
            val finalSafeIds = finalSafeActions.map { it.localId }
            if (finalSafeIds.isEmpty()) continue

            try {
                ops.deleteLocally(finalSafeIds)
                tracker.succeeded += finalSafeIds.size
            } catch (e: Throwable) {
                e.throwIfCancellation()
                for (action in finalSafeActions) {
                    diagnostics.localActionFailed(
                        entityName = entityName,
                        action = action,
                        error = e,
                    )
                    tracker.fail(action, e)
                }
            }
        }
    }

    private suspend fun executeInsertLocally(
        actions: List<SyncAction.InsertLocally>,
        plan: EntitySyncPlan<Local, Server>,
        tracker: ResultTracker,
    ) {
        if (actions.isEmpty()) return

        for (chunk in actions.chunked(BATCH_SIZE)) {
            coroutineContext.ensureActive()
            val entries = mutableListOf<Pair<Server, Local?>>()
            val attemptedActions = mutableListOf<SyncAction.InsertLocally>()
            for (action in chunk) {
                val serverEntity = plan.serverSnapshot.entitiesById[action.serverId]
                if (serverEntity != null) {
                    entries.add(serverEntity to null)
                    attemptedActions.add(action)
                } else {
                    tracker.skipped++
                }
            }
            if (entries.isNotEmpty()) {
                try {
                    ops.insertOrUpdateLocally(entries)
                    tracker.succeeded += entries.size
                } catch (e: Throwable) {
                    e.throwIfCancellation()
                    for (action in attemptedActions) {
                        diagnostics.localActionFailed(
                            entityName = entityName,
                            action = action,
                            error = e,
                        )
                        tracker.fail(action, e)
                    }
                }
            }
        }
    }

    private suspend fun executeUpdateLocally(
        actions: List<SyncAction.UpdateLocally>,
        plan: EntitySyncPlan<Local, Server>,
        snapshotMetaByLocalId: Map<String, LocalItemMeta>,
        tracker: ResultTracker,
    ) {
        if (actions.isEmpty()) return

        for (chunk in actions.chunked(BATCH_SIZE)) {
            coroutineContext.ensureActive()
            val entries = mutableListOf<LocalUpdateEntry<Server, Local>>()
            val passedActions = mutableListOf<SyncAction.UpdateLocally>()
            for (action in chunk) {
                val snapshotMeta = snapshotMetaByLocalId[action.localId]
                val result = checkConcurrency(action.localId, snapshotMeta)
                when (result.outcome) {
                    ConcurrencyOutcome.SAFE -> {
                        val serverEntity = plan.serverSnapshot.entitiesById[action.serverId]
                        val currentEntity = result.currentEntity
                        if (serverEntity != null && currentEntity != null) {
                            entries.add(
                                LocalUpdateEntry(
                                    localId = action.localId,
                                    server = serverEntity,
                                    initialLocal = currentEntity,
                                    shouldUpdate = { current ->
                                        if (current == null) {
                                            false
                                        } else if (snapshotMeta == null) {
                                            true
                                        } else {
                                            strategy.toLocalItemMeta(current) == snapshotMeta
                                        }
                                    },
                                ),
                            )
                            passedActions.add(action)
                        }
                    }

                    ConcurrencyOutcome.MODIFIED -> {
                        diagnostics.occSkipped(
                            entityName = entityName,
                            action = action,
                            outcome = OCC_MODIFIED,
                            snapshotMeta = snapshotMeta,
                        )
                        tracker.skipped++
                    }

                    ConcurrencyOutcome.DELETED -> {
                        diagnostics.occSkipped(
                            entityName = entityName,
                            action = action,
                            outcome = OCC_DELETED,
                            snapshotMeta = snapshotMeta,
                        )
                        tracker.skipped++
                    }
                }
            }
            if (entries.isNotEmpty()) {
                try {
                    val result = ops.updateLocally(entries)
                    tracker.succeeded += result.applied
                    tracker.skipped += result.skipped
                } catch (e: Throwable) {
                    e.throwIfCancellation()
                    for (action in passedActions) {
                        diagnostics.localActionFailed(
                            entityName = entityName,
                            action = action,
                            error = e,
                        )
                        tracker.fail(action, e)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Bulk remote operations
    // ---------------------------------------------------------------

    /**
     * Batches [SyncAction.DeleteOnServer] actions via [BulkRemoteOps.bulkDeleteOnServer],
     * chunked by [BULK_CHUNK_SIZE]. On any failure within a chunk, falls back to
     * individual [executeRemoteWithFinalization] calls for each entry in that chunk.
     */
    private suspend fun executeBulkDeleteOnServer(
        actions: List<SyncAction.DeleteOnServer>,
        snapshotMetaByLocalId: Map<String, LocalItemMeta>,
        tracker: ResultTracker,
    ) {
        val bulk = bulkRemoteOps ?: return

        // Collect concurrency-safe entries.
        data class SafeEntry(
            val action: SyncAction.DeleteOnServer,
            val currentEntity: Local,
            val operationStartMeta: LocalItemMeta,
        )

        val safeEntries = mutableListOf<SafeEntry>()
        for (action in actions) {
            val snapshotMeta = snapshotMetaByLocalId[action.localId]
            val result = checkConcurrency(action.localId, snapshotMeta)
            when (result.outcome) {
                ConcurrencyOutcome.SAFE -> {
                    val entity = result.currentEntity ?: continue
                    val meta = strategy.toLocalItemMeta(entity)
                    safeEntries.add(SafeEntry(action, entity, meta))
                }
                ConcurrencyOutcome.MODIFIED -> {
                    diagnostics.occSkipped(
                        entityName = entityName,
                        action = action,
                        outcome = OCC_MODIFIED,
                        snapshotMeta = snapshotMeta,
                    )
                    tracker.skipped++
                }
                ConcurrencyOutcome.DELETED -> {
                    diagnostics.occSkipped(
                        entityName = entityName,
                        action = action,
                        outcome = OCC_DELETED,
                        snapshotMeta = snapshotMeta,
                    )
                    tracker.skipped++
                }
            }
        }

        // Process in chunks via bulk API, fall back to individual on HTTP error.
        for (chunk in safeEntries.chunked(BULK_CHUNK_SIZE)) {
            coroutineContext.ensureActive()
            try {
                val pairs = chunk.map { it.currentEntity to it.action.serverId }
                diagnostics.bulkDeleteChunk(
                    entityName = entityName,
                    size = chunk.size,
                    localIds = chunk.map { it.action.localId },
                    remoteIds = chunk.map { it.action.serverId },
                )
                bulk.bulkDeleteOnServer(pairs)
                for (entry in chunk) {
                    finalizeRemoteSuccess(
                        action = entry.action,
                        localId = entry.action.localId,
                        operationStartEntity = entry.currentEntity,
                        operationStartMeta = entry.operationStartMeta,
                        outcome = RemoteWriteOutcome.DeleteLocal,
                        tracker = tracker,
                    )
                }
            } catch (e: Throwable) {
                e.throwIfCancellation()
                diagnostics.bulkFallback(
                    entityName = entityName,
                    size = chunk.size,
                    error = e,
                )
                // Bulk API not supported or failed — fall back to individual.
                for (entry in chunk) {
                    coroutineContext.ensureActive()
                    executeRemoteWithFinalization(
                        action = entry.action,
                        localId = entry.action.localId,
                        snapshotMeta = snapshotMetaByLocalId[entry.action.localId],
                        tracker = tracker,
                    ) { currentEntity ->
                        ops.deleteOnServer(currentEntity, entry.action.serverId)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Individual remote operations with finalization
    // ---------------------------------------------------------------

    /**
     * Runs a single remote operation ([block]) with full OCC lifecycle:
     * 1. Pre-check concurrency (skip if entity changed/deleted).
     * 2. Execute the remote call.
     * 3. Finalize: on success → [finalizeRemoteSuccess];
     *    on typed failure → [finalizeRemoteFailure] with partial entity;
     *    on unexpected errors → [finalizeRemoteFailure] without partial entity.
     */
    private suspend fun executeRemoteWithFinalization(
        action: SyncAction,
        localId: String,
        snapshotMeta: LocalItemMeta?,
        tracker: ResultTracker,
        block: suspend (Local) -> RemoteWriteOutcome<Local>,
    ) {
        val result = checkConcurrency(localId, snapshotMeta)
        when (result.outcome) {
            ConcurrencyOutcome.SAFE -> {
                val operationStartEntity =
                    result.currentEntity
                        ?: return
                val operationStartMeta = strategy.toLocalItemMeta(operationStartEntity)
                try {
                    val outcome = block(operationStartEntity)
                    when (outcome) {
                        is RemoteWriteOutcome.Success -> finalizeRemoteSuccess(
                            action = action,
                            localId = localId,
                            operationStartEntity = operationStartEntity,
                            operationStartMeta = operationStartMeta,
                            outcome = outcome,
                            tracker = tracker,
                        )

                        is RemoteWriteOutcome.Failure -> finalizeRemoteFailure(
                            action = action,
                            localId = localId,
                            operationStartEntity = operationStartEntity,
                            operationStartMeta = operationStartMeta,
                            partialRemoteLocal = outcome.partialRemoteLocal,
                            error = outcome.cause,
                            tracker = tracker,
                        )
                    }
                } catch (e: Throwable) {
                    e.throwIfCancellation()
                    finalizeRemoteFailure(
                        action = action,
                        localId = localId,
                        operationStartEntity = operationStartEntity,
                        operationStartMeta = operationStartMeta,
                        partialRemoteLocal = null,
                        error = e,
                        tracker = tracker,
                    )
                }
            }

            ConcurrencyOutcome.MODIFIED -> {
                diagnostics.occSkipped(
                    entityName = entityName,
                    action = action,
                    outcome = OCC_MODIFIED,
                    snapshotMeta = snapshotMeta,
                )
                tracker.skipped++
            }

            ConcurrencyOutcome.DELETED -> {
                diagnostics.occSkipped(
                    entityName = entityName,
                    action = action,
                    outcome = OCC_DELETED,
                    snapshotMeta = snapshotMeta,
                )
                tracker.skipped++
            }
        }
    }

    /**
     * Persists the result of a successful remote write. If the entity
     * was concurrently modified, merges remote metadata into the
     * user's local changes via [EntitySyncOps.mergeRemoteSuccessIntoChangedLocal].
     */
    private suspend fun finalizeRemoteSuccess(
        action: SyncAction,
        localId: String,
        operationStartEntity: Local,
        operationStartMeta: LocalItemMeta,
        outcome: RemoteWriteOutcome.Success<Local>,
        tracker: ResultTracker,
    ) {
        val currentResult = checkConcurrency(localId, operationStartMeta)
        when (currentResult.outcome) {
            ConcurrencyOutcome.SAFE -> {
                try {
                    when (outcome) {
                        is RemoteWriteOutcome.Upsert -> ops.saveLocal(
                            local = outcome.local,
                            previousLocal = operationStartEntity,
                        )
                        RemoteWriteOutcome.DeleteLocal -> ops.deleteLocally(listOf(localId))
                    }
                    diagnostics.remoteActionSucceeded(
                        entityName = entityName,
                        action = action,
                    )
                    tracker.succeeded++
                } catch (e: Throwable) {
                    e.throwIfCancellation()
                    diagnostics.localActionFailed(
                        entityName = entityName,
                        action = action,
                        error = e,
                    )
                    tracker.fail(action, e)
                }
            }

            ConcurrencyOutcome.MODIFIED -> {
                diagnostics.occSkipped(
                    entityName = entityName,
                    action = action,
                    outcome = OCC_MODIFIED,
                    snapshotMeta = operationStartMeta,
                )
                val current = currentResult.currentEntity
                if (current == null) {
                    tracker.skipped++
                    return
                }
                try {
                    when (outcome) {
                        is RemoteWriteOutcome.Upsert -> {
                            val reconciled =
                                ops.mergeRemoteSuccessIntoChangedLocal(
                                    current = current,
                                    remoteLocal = outcome.local,
                                )
                            ops.saveLocal(
                                local = reconciled,
                                previousLocal = current,
                            )
                        }

                        RemoteWriteOutcome.DeleteLocal -> {
                            val detached = ops.detachRemoteAfterDeletedOnServer(current)
                            ops.saveLocal(
                                local = detached,
                                previousLocal = current,
                            )
                        }
                    }
                    diagnostics.remoteActionSucceeded(
                        entityName = entityName,
                        action = action,
                    )
                    tracker.skipped++
                } catch (e: Throwable) {
                    e.throwIfCancellation()
                    diagnostics.localActionFailed(
                        entityName = entityName,
                        action = action,
                        error = e,
                    )
                    tracker.fail(action, e)
                }
            }

            ConcurrencyOutcome.DELETED -> {
                diagnostics.occSkipped(
                    entityName = entityName,
                    action = action,
                    outcome = OCC_DELETED,
                    snapshotMeta = operationStartMeta,
                )
                tracker.skipped++
            }
        }
    }

    /**
     * Records a failed remote write on the entity's service metadata.
     * If the entity was concurrently modified and a [partialRemoteLocal]
     * is available, merges the partial remote metadata into the current entity.
     */
    private suspend fun finalizeRemoteFailure(
        action: SyncAction,
        localId: String,
        operationStartEntity: Local,
        operationStartMeta: LocalItemMeta,
        partialRemoteLocal: Local?,
        error: Throwable,
        tracker: ResultTracker,
    ) {
        val currentResult = checkConcurrency(localId, operationStartMeta)
        try {
            when (currentResult.outcome) {
                ConcurrencyOutcome.SAFE -> {
                    val failedLocal =
                        ops.markRemoteFailure(
                            local = operationStartEntity,
                            remoteLocal = partialRemoteLocal,
                            error = error,
                        )
                    ops.saveLocal(
                        local = failedLocal,
                        previousLocal = operationStartEntity,
                    )
                }

                ConcurrencyOutcome.MODIFIED -> {
                    diagnostics.occSkipped(
                        entityName = entityName,
                        action = action,
                        outcome = OCC_MODIFIED,
                        snapshotMeta = operationStartMeta,
                    )
                    val current = currentResult.currentEntity
                    if (current != null && partialRemoteLocal != null) {
                        val reconciled =
                            ops.mergeRemoteFailureIntoChangedLocal(
                                current = current,
                                remoteLocal = partialRemoteLocal,
                                error = error,
                            )
                        ops.saveLocal(
                            local = reconciled,
                            previousLocal = current,
                        )
                    }
                }

                ConcurrencyOutcome.DELETED -> {
                    diagnostics.occSkipped(
                        entityName = entityName,
                        action = action,
                        outcome = OCC_DELETED,
                        snapshotMeta = operationStartMeta,
                    )
                }
            }
        } catch (e: Throwable) {
            e.throwIfCancellation()
            // The remote action already failed. A secondary failure while
            // persisting failure metadata must not count as another action.
        }
        diagnostics.remoteActionFailed(
            entityName = entityName,
            action = action,
            error = error,
        )
        tracker.fail(action, error)
    }

    // ---------------------------------------------------------------
    // Concurrency check
    // ---------------------------------------------------------------

    /**
     * Re-reads the entity from the database and compares its current
     * metadata against the [snapshotMeta] captured at plan-build time.
     * Returns [ConcurrencyOutcome.SAFE] only if the metadata matches exactly.
     */
    private suspend fun checkConcurrency(
        localId: String,
        snapshotMeta: LocalItemMeta?,
    ): ConcurrencyCheckResult<Local> {
        val current = ops.readLocal(localId)
        if (current == null) {
            return ConcurrencyCheckResult(
                outcome = ConcurrencyOutcome.DELETED,
                currentEntity = null,
            )
        }
        if (snapshotMeta == null) {
            return ConcurrencyCheckResult(
                outcome = ConcurrencyOutcome.SAFE,
                currentEntity = current,
            )
        }
        val currentMeta = strategy.toLocalItemMeta(current)
        return if (currentMeta == snapshotMeta) {
            ConcurrencyCheckResult(
                outcome = ConcurrencyOutcome.SAFE,
                currentEntity = current,
            )
        } else {
            ConcurrencyCheckResult(
                outcome = ConcurrencyOutcome.MODIFIED,
                currentEntity = current,
            )
        }
    }

    // ---------------------------------------------------------------
    // Result tracking
    // ---------------------------------------------------------------

    /** Mutable accumulator for succeeded/skipped/failed action counts. */
    private class ResultTracker(
        var staleServerEntities: Int = 0,
    ) {
        var succeeded: Int = 0
        var skipped: Int = 0
        val failures: MutableList<ActionFailure> = mutableListOf()
        var lastPhaseSnapshot: SyncExecutionResult = SyncExecutionResult()

        fun fail(
            action: SyncAction,
            error: Throwable,
        ) {
            failures.add(ActionFailure(action = action, error = error))
        }

        fun toResult() =
            SyncExecutionResult(
                succeeded = succeeded,
                skipped = skipped,
                failures = failures.toList(),
                staleServerEntities = staleServerEntities,
            )
    }
}

private const val PHASE_DELETE_LOCALLY = "delete_locally"
private const val PHASE_INSERT_LOCALLY = "insert_locally"
private const val PHASE_UPDATE_LOCALLY = "update_locally"
private const val PHASE_MERGE_CONFLICT = "merge_conflict"
private const val PHASE_DELETE_ON_SERVER = "delete_on_server"
private const val PHASE_DELETE_ON_SERVER_BULK = "delete_on_server_bulk"
private const val PHASE_PUSH_TO_SERVER = "push_to_server"

private const val OCC_MODIFIED = "modified"
private const val OCC_DELETED = "deleted"
