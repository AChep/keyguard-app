package com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.strategy

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollection
import com.artemchep.keyguard.provider.bitwarden.entity.CollectionEntity
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.EntitySyncStrategy
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.buildLocalItemMeta
import kotlin.time.Instant

/**
 * Sync strategy for Bitwarden collections.
 *
 * Collections are read-only from the client side. The server
 * revision date is set to [Instant.DISTANT_FUTURE] so that the
 * server version always wins in diff comparisons.
 */
class CollectionSyncStrategy : EntitySyncStrategy<BitwardenCollection, CollectionEntity> {
    override fun toLocalItemMeta(entity: BitwardenCollection): LocalItemMeta =
        buildLocalItemMeta(
            localId = entity.collectionId,
            service = entity.service,
            revisionDate = entity.revisionDate,
            deletedDate = entity.deletedDate,
            isMergeable = false,
        )

    override fun toServerItemMeta(entity: CollectionEntity): ServerItemMeta =
        ServerItemMeta(
            id = entity.id,
            revisionDate = Instant.DISTANT_FUTURE,
            deletedDate = null,
        )
}
