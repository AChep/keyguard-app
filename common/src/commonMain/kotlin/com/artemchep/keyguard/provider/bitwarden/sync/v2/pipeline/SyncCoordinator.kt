package com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntityTypeOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncExecutionResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.throwIfCancellation

/**
 * Orchestrates the full Snapshot → Diff → Plan → Execute pipeline
 * for a single entity type.
 *
 * [safeSyncEntityType] wraps the pipeline in error isolation:
 * non-cancellation exceptions are captured as [EntityTypeOutcome.Failed]
 * so that one entity type's failure does not abort the others.
 */
class SyncCoordinator {
    /**
     * Runs the sync pipeline for a single entity type.
     *
     * @throws Exception on any unrecoverable error (propagated to caller).
     */
    suspend fun <Local : BitwardenService.Has<Local>, Server : Any> syncEntityType(
        config: EntitySyncConfig<Local, Server>,
    ): SyncExecutionResult {
        val planBuilder = EntitySyncPlanBuilder(config.strategy)
        val plan =
            planBuilder.buildPlan(
                localEntities = config.localEntities,
                serverEntities = config.serverEntities,
            )
        val executor =
            EntitySyncExecutor(
                strategy = config.strategy,
                ops = config.ops,
                bulkRemoteOps = config.bulkRemoteOps,
            )
        return executor.execute(plan)
    }

    /**
     * Like [syncEntityType], but captures non-cancellation exceptions
     * as [EntityTypeOutcome.Failed] instead of propagating them.
     */
    suspend fun <Local : BitwardenService.Has<Local>, Server : Any> safeSyncEntityType(
        config: EntitySyncConfig<Local, Server>,
    ): EntityTypeOutcome =
        try {
            EntityTypeOutcome.Completed(syncEntityType(config))
        } catch (e: Throwable) {
            e.throwIfCancellation()
            EntityTypeOutcome.Failed(error = e)
        }
}
