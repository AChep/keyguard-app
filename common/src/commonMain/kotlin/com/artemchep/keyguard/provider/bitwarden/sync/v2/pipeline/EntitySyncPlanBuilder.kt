package com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntitySyncPlan
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalEntitySnapshot
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerEntitySnapshot
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncDiffer
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.EntitySyncStrategy

/**
 * Builds an [EntitySyncPlan] by taking snapshots of local and server
 * entities and running [SyncDiffer.diff] to produce the action list.
 *
 * This is a pure computation step — no I/O or side effects.
 */
class EntitySyncPlanBuilder<Local : BitwardenService.Has<Local>, Server : Any>(
    private val strategy: EntitySyncStrategy<Local, Server>,
) {
    fun buildLocalSnapshot(entities: List<Local>): LocalEntitySnapshot<Local> {
        val metadata = ArrayList<LocalItemMeta>(entities.size)
        val byLocalId = LinkedHashMap<String, Local>(entities.size)
        for (entity in entities) {
            val meta = strategy.toLocalItemMeta(entity)
            metadata.add(meta)
            byLocalId[meta.localId] = entity
        }
        return LocalEntitySnapshot(
            entitiesByLocalId = byLocalId,
            metadata = metadata,
        )
    }

    fun buildServerSnapshot(entities: List<Server>): ServerEntitySnapshot<Server> {
        val metadata = ArrayList<ServerItemMeta>(entities.size)
        val byId = LinkedHashMap<String, Server>(entities.size)
        for (entity in entities) {
            val meta = strategy.toServerItemMeta(entity)
            metadata.add(meta)
            byId[meta.id] = entity
        }
        return ServerEntitySnapshot(
            entitiesById = byId,
            metadata = metadata,
        )
    }

    fun buildPlan(
        localEntities: List<Local>,
        serverEntities: List<Server>,
    ): EntitySyncPlan<Local, Server> {
        val localSnapshot = buildLocalSnapshot(localEntities)
        val serverSnapshot = buildServerSnapshot(serverEntities)
        val actions =
            SyncDiffer.diff(
                localItems = localSnapshot.metadata,
                serverItems = serverSnapshot.metadata,
            )
        return EntitySyncPlan(
            actions = actions,
            localSnapshot = localSnapshot,
            serverSnapshot = serverSnapshot,
        )
    }
}
