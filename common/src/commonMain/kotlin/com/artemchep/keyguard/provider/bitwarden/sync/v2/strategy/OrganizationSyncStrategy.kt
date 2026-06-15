package com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy

import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganization
import com.artemchep.keyguard.provider.bitwarden.entity.OrganizationEntity
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerItemMeta
import kotlin.time.Instant

/**
 * Sync strategy for Bitwarden organizations.
 *
 * Organizations are read-only from the client side. The server
 * revision date is set to [Instant.DISTANT_FUTURE] so that the
 * server version always wins in diff comparisons.
 */
class OrganizationSyncStrategy : EntitySyncStrategy<BitwardenOrganization, OrganizationEntity> {
    override fun toLocalItemMeta(entity: BitwardenOrganization): LocalItemMeta =
        buildLocalItemMeta(
            localId = entity.organizationId,
            service = entity.service,
            revisionDate = entity.revisionDate,
            deletedDate = entity.deletedDate,
            isMergeable = false,
        )

    override fun toServerItemMeta(entity: OrganizationEntity): ServerItemMeta =
        ServerItemMeta(
            id = entity.id,
            revisionDate = Instant.DISTANT_FUTURE,
            deletedDate = null,
        )
}
