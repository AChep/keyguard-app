package com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.strategy

import com.artemchep.keyguard.common.util.isOver6DigitsNanosOfSecond
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.EntitySyncStrategy
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.buildCipherLocalItemMeta

/**
 * Sync strategy for Bitwarden ciphers.
 *
 * Ciphers are the only entity type with `isMergeable = true`, enabling
 * three-way merge when both local and server sides have diverged.
 *
 * Tracks additional drift-detection fields (attachments, folder,
 * favorite, collections) and handles repair scenarios.
 */
class CipherSyncStrategy(
    private val remoteFolderIdToLocalId: (String) -> String?,
) : EntitySyncStrategy<BitwardenCipher, CipherEntity> {
    override fun toLocalItemMeta(entity: BitwardenCipher): LocalItemMeta =
        buildCipherLocalItemMeta(entity).copy(
            collectionIds = entity.collectionIds,
            requiresForcePushWhenDatesMatch = entity.hasEqualDateForcePushRepair(),
        )

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
            localFolderId = entity.folderId?.let(remoteFolderIdToLocalId),
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
