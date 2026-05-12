package com.artemchep.keyguard.provider.bitwarden.sync.v2.core

/**
 * Immutable snapshot of local entities taken at the start of sync.
 *
 * Provides O(1) lookup by local ID and the ordered metadata list
 * used by [SyncDiffer] for diff computation.
 */
data class LocalEntitySnapshot<Local>(
    val entitiesByLocalId: Map<String, Local>,
    val metadata: List<LocalItemMeta>,
)

/**
 * Immutable snapshot of server entities from the `GET /sync` response.
 *
 * Provides O(1) lookup by server ID and the ordered metadata list
 * used by [SyncDiffer].
 */
data class ServerEntitySnapshot<Server>(
    val entitiesById: Map<String, Server>,
    val metadata: List<ServerItemMeta>,
)

/**
 * The output of [EntitySyncPlanBuilder][com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncPlanBuilder]:
 * a list of [SyncAction]s together with the snapshots they reference.
 *
 * Passed to [EntitySyncExecutor][com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncExecutor]
 * for execution.
 */
data class EntitySyncPlan<Local, Server>(
    val actions: List<SyncAction>,
    val localSnapshot: LocalEntitySnapshot<Local>,
    val serverSnapshot: ServerEntitySnapshot<Server>,
    val staleServerEntities: Int = 0,
)
