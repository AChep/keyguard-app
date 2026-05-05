package com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.canRetry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncDiffer
import kotlin.time.Instant

/**
 * Builds a [LocalItemMeta] from [BitwardenService] metadata and
 * entity-specific fields.
 *
 * Shared by all [EntitySyncStrategy] implementations to avoid
 * duplicating the service-to-meta mapping logic.
 */
internal fun buildLocalItemMeta(
    localId: String,
    service: BitwardenService,
    revisionDate: Instant,
    deletedDate: Instant?,
    isMergeable: Boolean,
    attachmentIds: Set<String>? = null,
    folderId: String? = null,
    favorite: Boolean? = null,
    collectionIds: Set<String>? = null,
    requiresLocalRefreshWhenDatesMatch: Boolean = false,
    requiresPushWhenDatesMatch: Boolean = false,
    requiresForcePushWhenDatesMatch: Boolean = false,
    pendingLocalAttachmentIds: Set<String> = emptySet(),
    pendingRemoteAttachmentDeletionIds: Set<String> = emptySet(),
): LocalItemMeta {
    val effectiveDate = SyncDiffer.effectiveDate(revisionDate, deletedDate)
    val error = service.error
    return LocalItemMeta(
        localId = localId,
        remoteId = service.remote?.id,
        revisionDate = revisionDate,
        deletedDate = deletedDate,
        lastSyncedRevisionDate = service.remote?.revisionDate,
        lastSyncedDeletedDate = service.remote?.deletedDate,
        isLocallyDeleted = service.deleted,
        isMergeable = isMergeable,
        serviceVersion = service.version,
        hasError = error != null,
        canRetryError = error?.canRetry(effectiveDate) != false,
        attachmentIds = attachmentIds,
        folderId = folderId,
        favorite = favorite,
        collectionIds = collectionIds,
        requiresLocalRefreshWhenDatesMatch = requiresLocalRefreshWhenDatesMatch,
        requiresPushWhenDatesMatch = requiresPushWhenDatesMatch,
        requiresForcePushWhenDatesMatch = requiresForcePushWhenDatesMatch,
        pendingLocalAttachmentIds = pendingLocalAttachmentIds,
        pendingRemoteAttachmentDeletionIds = pendingRemoteAttachmentDeletionIds,
    )
}
