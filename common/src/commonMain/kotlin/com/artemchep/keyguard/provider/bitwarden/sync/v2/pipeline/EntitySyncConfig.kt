package com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.EntitySyncStrategy

/**
 * Configuration bundle for syncing a single entity type.
 *
 * Passed to [SyncCoordinator.safeSyncEntityType] which builds the
 * plan and executes it.
 *
 * @param name human-readable name for logging (e.g. "ciphers").
 * @param strategy metadata extractor for diff computation.
 * @param localEntities current local entities for this account.
 * @param serverEntities entities from the `GET /sync` response.
 * @param ops entity-specific operations (DB, API, crypto).
 * @param bulkRemoteOps optional bulk server operations; when non-null,
 *   the executor batches delete/restore/trash calls instead of
 *   processing them individually.
 */
data class EntitySyncConfig<Local : BitwardenService.Has<Local>, Server : Any>(
    val name: String,
    val strategy: EntitySyncStrategy<Local, Server>,
    val localEntities: List<Local>,
    val serverEntities: List<Server>,
    val ops: EntitySyncOps<Local, Server>,
    val bulkRemoteOps: BulkRemoteOps<Local>? = null,
)
