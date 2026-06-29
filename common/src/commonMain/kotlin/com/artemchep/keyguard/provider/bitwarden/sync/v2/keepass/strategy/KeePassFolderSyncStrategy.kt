package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.strategy

import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity.KeePassFolder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.EntitySyncStrategy
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.buildLocalItemMeta

class KeePassFolderSyncStrategy(
    private val remoteFolderIdToLocalId: (String) -> String?,
) : EntitySyncStrategy<BitwardenFolder, KeePassFolder> {
    override fun toLocalItemMeta(entity: BitwardenFolder): LocalItemMeta =
        buildLocalItemMeta(
            localId = entity.folderId,
            service = entity.service,
            revisionDate = entity.revisionDate,
            deletedDate = null,
            isMergeable = false,
            parentFolderId = entity.parentId,
            folderHierarchyMode = entity.hierarchyMode,
        )

    override fun toServerItemMeta(entity: KeePassFolder): ServerItemMeta =
        ServerItemMeta(
            id = entity.id,
            revisionDate = entity.revisionDate,
            deletedDate = null,
            parentFolderId = entity.parentGroupUuid
                ?.toString()
                ?.let(remoteFolderIdToLocalId),
            folderHierarchyMode = FolderHierarchyMode.ParentId,
        )
}
