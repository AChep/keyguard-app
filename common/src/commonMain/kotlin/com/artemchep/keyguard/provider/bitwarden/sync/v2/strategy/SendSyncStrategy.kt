package com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy

import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.hasPendingFileUpload
import com.artemchep.keyguard.provider.bitwarden.entity.SendEntity
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerItemMeta

/**
 * Sync strategy for Bitwarden Sends.
 *
 * Sends are not mergeable and have no soft-delete in the sync
 * sense (`deletedDate = null`). The send's `deletionDate` field
 * is a scheduled auto-deletion timestamp, not a sync tombstone.
 *
 * [withoutRemoteDeletedDate] strips the remote's `deletedDate`
 * from service metadata to prevent the differ from treating it
 * as a deletion signal.
 */
class SendSyncStrategy : EntitySyncStrategy<BitwardenSend, SendEntity> {
    override fun toLocalItemMeta(entity: BitwardenSend): LocalItemMeta =
        buildLocalItemMeta(
            localId = entity.sendId,
            service = entity.service.withoutRemoteDeletedDate(),
            revisionDate = entity.revisionDate,
            deletedDate = null,
            isMergeable = false,
            requiresPushWhenDatesMatch = entity.hasPendingFileUpload(),
        )

    override fun toServerItemMeta(entity: SendEntity): ServerItemMeta =
        ServerItemMeta(
            id = entity.id,
            revisionDate = entity.revisionDate,
            deletedDate = null,
        )
}

private fun BitwardenService.withoutRemoteDeletedDate(): BitwardenService =
    copy(
        remote = remote?.copy(deletedDate = null),
    )
