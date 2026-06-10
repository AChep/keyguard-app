package com.artemchep.keyguard.provider.bitwarden.sync.v2.core

import kotlin.time.Instant

/**
 * Metadata extracted from a local entity for diff computation.
 *
 * All fields participate in data-class equality, which drives the
 * optimistic concurrency check in [EntitySyncExecutor][com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncExecutor]:
 * if any field changes between snapshot and re-read, the action
 * is skipped.
 *
 * @param localId the local database primary key.
 * @param remoteId the server-side UUID, or `null` for new local-only entities.
 * @param revisionDate the entity's revision timestamp.
 * @param deletedDate soft-delete timestamp, or `null` if not deleted.
 * @param lastSyncedRevisionDate the revision date at last successful sync.
 * @param lastSyncedDeletedDate the deleted date at last successful sync.
 * @param isLocallyDeleted `true` if the user deleted this locally
 *   (pending server-side propagation).
 * @param isMergeable `true` if this entity type supports three-way merge
 *   (currently only ciphers).
 * @param serviceVersion the [BitwardenService.VERSION][com.artemchep.keyguard.core.store.bitwarden.BitwardenService.VERSION]
 *   at the time of last decode; triggers re-decode on schema upgrades.
 * @param hasError `true` if the entity has a pending sync error.
 * @param canRetryError `true` if the error is transient and retryable.
 * @param attachmentIds set of attachment IDs for drift detection.
 * @param localFolderId local folder ID used for folder drift detection
 *   against [ServerItemMeta.localFolderId]. Unlike most fields, `null`
 *   is a real value here ("no folder").
 * @param favorite favorite flag for drift detection.
 * @param collectionIds set of collection IDs for drift detection.
 * @param requiresLocalRefreshWhenDatesMatch forces a local update even
 *   when dates match (e.g. decoding errors that may resolve on retry).
 * @param requiresPushWhenDatesMatch runs the server write pipeline even
 *   when dates match, without forcing the entity body to be rewritten
 *   (e.g. pending file or attachment uploads).
 * @param requiresForcePushWhenDatesMatch forces a server push even
 *   when dates match (e.g. date-rounding repair).
 * @param pendingLocalAttachmentIds local attachment IDs whose upload
 *   still needs server-side processing.
 * @param pendingRemoteAttachmentDeletionIds remote attachment IDs whose
 *   deletion still needs server-side processing.
 */
data class LocalItemMeta(
    val localId: String,
    val remoteId: String?,
    val revisionDate: Instant,
    val deletedDate: Instant? = null,
    val lastSyncedRevisionDate: Instant? = null,
    val lastSyncedDeletedDate: Instant? = null,
    val isLocallyDeleted: Boolean = false,
    val isMergeable: Boolean = false,
    val serviceVersion: Int = 0,
    val hasError: Boolean = false,
    val canRetryError: Boolean = true,
    val attachmentIds: Set<String>? = null,
    val localFolderId: String? = null,
    val favorite: Boolean? = null,
    val collectionIds: Set<String>? = null,
    val requiresLocalRefreshWhenDatesMatch: Boolean = false,
    val requiresPushWhenDatesMatch: Boolean = false,
    val requiresForcePushWhenDatesMatch: Boolean = false,
    val pendingLocalAttachmentIds: Set<String> = emptySet(),
    val pendingRemoteAttachmentDeletionIds: Set<String> = emptySet(),
)

/**
 * Metadata extracted from a server entity for diff computation.
 *
 * @param id the server-side UUID.
 * @param revisionDate the server entity's revision timestamp.
 * @param deletedDate soft-delete timestamp from the server, or `null`.
 * @param attachmentIds set of attachment IDs for drift detection against local state.
 * @param localFolderId the server entity's folder reference resolved to
 *   the local folder ID namespace. Used for folder drift
 *   detection against [LocalItemMeta.localFolderId].
 * @param favorite favorite flag for drift detection.
 * @param collectionIds set of collection IDs for drift detection.
 */
data class ServerItemMeta(
    val id: String,
    val revisionDate: Instant,
    val deletedDate: Instant? = null,
    val attachmentIds: Set<String>? = null,
    val localFolderId: String? = null,
    val favorite: Boolean? = null,
    val collectionIds: Set<String>? = null,
)
