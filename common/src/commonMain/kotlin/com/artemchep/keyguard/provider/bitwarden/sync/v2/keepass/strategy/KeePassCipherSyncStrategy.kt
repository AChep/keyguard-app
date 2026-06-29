package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.strategy

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity.KeePassCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.EntitySyncStrategy
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.buildLocalItemMeta

class KeePassCipherSyncStrategy(
    private val remoteFolderIdToLocalId: (String) -> String?,
) : EntitySyncStrategy<BitwardenCipher, KeePassCipher> {
    override fun toLocalItemMeta(entity: BitwardenCipher): LocalItemMeta =
        buildLocalItemMeta(
            localId = entity.cipherId,
            service = entity.service,
            revisionDate = entity.revisionDate,
            deletedDate = entity.deletedDate,
            isMergeable = true,
            attachmentIds = entity.attachments
                .map { it.id }
                .toSet(),
            localFolderId = entity.folderId,
            favorite = entity.favorite,
        )

    override fun toServerItemMeta(entity: KeePassCipher): ServerItemMeta =
        ServerItemMeta(
            id = entity.id,
            revisionDate = entity.revisionDate,
            deletedDate = entity.deletedDate,
            localFolderId = remoteFolderIdToLocalId(entity.group.uuid.toString()),
        )
}
