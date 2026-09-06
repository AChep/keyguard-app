package com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy

import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.canRetry
import com.artemchep.keyguard.core.store.bitwarden.pendingLocalAttachments
import com.artemchep.keyguard.core.store.bitwarden.pendingRemoteAttachmentDeletionIds
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
    localFolderId: String? = null,
    parentFolderId: String? = null,
    folderHierarchyMode: FolderHierarchyMode? = null,
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
        localFolderId = localFolderId,
        parentFolderId = parentFolderId,
        folderHierarchyMode = folderHierarchyMode,
        favorite = favorite,
        collectionIds = collectionIds,
        requiresLocalRefreshWhenDatesMatch = requiresLocalRefreshWhenDatesMatch,
        requiresPushWhenDatesMatch = requiresPushWhenDatesMatch,
        requiresForcePushWhenDatesMatch = requiresForcePushWhenDatesMatch,
        pendingLocalAttachmentIds = pendingLocalAttachmentIds,
        pendingRemoteAttachmentDeletionIds = pendingRemoteAttachmentDeletionIds,
    )
}

/**
 * Builds the local sync metadata shared by all cipher providers.
 *
 * Provider-specific metadata, such as Bitwarden collections and timestamp
 * repair markers, should be added by the provider strategy.
 */
internal fun buildCipherLocalItemMeta(entity: BitwardenCipher): LocalItemMeta {
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
        localFolderId = entity.folderId,
        favorite = entity.favorite,
        requiresLocalRefreshWhenDatesMatch = entity.remoteEntity == null,
        requiresPushWhenDatesMatch =
            pendingLocalAttachmentIds.isNotEmpty() ||
                pendingRemoteAttachmentDeletionIds.isNotEmpty(),
        pendingLocalAttachmentIds = pendingLocalAttachmentIds,
        pendingRemoteAttachmentDeletionIds = pendingRemoteAttachmentDeletionIds,
    )
}
