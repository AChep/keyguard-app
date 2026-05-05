package com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy

import com.artemchep.keyguard.common.util.isOver6DigitsNanosOfSecond
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.pendingLocalAttachments
import com.artemchep.keyguard.core.store.bitwarden.pendingRemoteAttachmentDeletionIds
import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerItemMeta

/**
 * Sync strategy for Bitwarden ciphers.
 *
 * Ciphers are the only entity type with `isMergeable = true`, enabling
 * three-way merge when both local and server sides have diverged.
 *
 * Tracks additional drift-detection fields (attachments, folder,
 * favorite, collections) and handles two repair scenarios:
 * - `requiresLocalRefreshWhenDatesMatch`: forces re-decode when
 *   [BitwardenCipher.remoteEntity] is missing (lost merge base).
 * - `requiresPushWhenDatesMatch`: runs the push pipeline when
 *   pending attachment mutations need to be retried.
 * - `requiresForcePushWhenDatesMatch`: forces a server push when
 *   sub-second timestamp precision exceeds 6 digits of nanoseconds,
 *   which some servers silently truncate.
 */
class CipherSyncStrategy : EntitySyncStrategy<BitwardenCipher, CipherEntity> {
    override fun toLocalItemMeta(entity: BitwardenCipher): LocalItemMeta {
        val pendingLocalAttachmentIds =
            entity.pendingLocalAttachments()
                .asSequence()
                .map { it.id }
                .toSet()
        val pendingRemoteAttachmentDeletionIds =
            entity.pendingRemoteAttachmentDeletionIds()
        return buildLocalItemMeta(
            localId = entity.cipherId,
            service = entity.service,
            revisionDate = entity.revisionDate,
            deletedDate = entity.deletedDate,
            isMergeable = true,
            attachmentIds =
                entity.attachments
                    .asSequence()
                    .map { it.id }
                    .toSet(),
            folderId = entity.folderId,
            favorite = entity.favorite,
            collectionIds = entity.collectionIds,
            requiresLocalRefreshWhenDatesMatch = entity.remoteEntity == null,
            requiresPushWhenDatesMatch =
                pendingLocalAttachmentIds.isNotEmpty() ||
                    pendingRemoteAttachmentDeletionIds.isNotEmpty(),
            requiresForcePushWhenDatesMatch = entity.hasEqualDateForcePushRepair(),
            pendingLocalAttachmentIds = pendingLocalAttachmentIds,
            pendingRemoteAttachmentDeletionIds = pendingRemoteAttachmentDeletionIds,
        )
    }

    override fun toServerItemMeta(entity: CipherEntity): ServerItemMeta =
        ServerItemMeta(
            id = entity.id,
            revisionDate = entity.revisionDate,
            deletedDate = entity.deletedDate,
            attachmentIds =
                entity.attachments
                    .orEmpty()
                    .asSequence()
                    .map { it.id }
                    .toSet(),
            folderId = entity.folderId,
            favorite = entity.favorite,
            collectionIds = entity.collectionIds?.toSet(),
        )
}

private fun BitwardenCipher.hasEqualDateForcePushRepair(): Boolean {
    val passwordRevision = login?.passwordRevisionDate?.isOver6DigitsNanosOfSecond() == true
    val passwordHistoryPrecision =
        passwordHistory.any { it.lastUsedDate?.isOver6DigitsNanosOfSecond() == true }
    val passkeyCreatedAtPrecision =
        login?.fido2Credentials.orEmpty().any { it.creationDate.isOver6DigitsNanosOfSecond() }
    return passwordRevision || passwordHistoryPrecision || passkeyCreatedAtPrecision
}
