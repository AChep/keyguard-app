package com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy

import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.provider.bitwarden.entity.FolderEntity
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerItemMeta

/**
 * Sync strategy for Bitwarden folders.
 *
 * Folders are not mergeable (`isMergeable = false`) and have no
 * soft-delete support (`deletedDate = null`).
 */
class FolderSyncStrategy : EntitySyncStrategy<BitwardenFolder, FolderEntity> {
    override fun toLocalItemMeta(entity: BitwardenFolder): LocalItemMeta =
        buildLocalItemMeta(
            localId = entity.folderId,
            service = entity.service,
            revisionDate = entity.revisionDate,
            deletedDate = null,
            isMergeable = false,
        )

    override fun toServerItemMeta(entity: FolderEntity): ServerItemMeta =
        ServerItemMeta(
            id = entity.id,
            revisionDate = entity.revisionDate,
            deletedDate = null,
        )
}
