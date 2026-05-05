package com.artemchep.keyguard.provider.bitwarden.sync.v2.core

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * Pure diff engine that compares local and server metadata to produce
 * a list of [SyncAction]s.
 *
 * The algorithm handles:
 * - **New server items** → [SyncAction.InsertLocally]
 * - **Deleted server items** → [SyncAction.DeleteLocally]
 * - **Updated server items** → [SyncAction.UpdateLocally]
 * - **Locally modified items** → [SyncAction.PushToServer] or
 *   [SyncAction.DeleteOnServer]
 * - **Conflicting edits** → [SyncAction.MergeConflict] (for mergeable types)
 * - **Duplicate local entries** for the same remote ID → keeps the
 *   most recent, deletes the rest
 * - **Date-rounding mismatches** → forces a local refresh
 * - **Service-version upgrades** → forces re-decode via
 *   [SyncAction.UpdateLocally] with `force = true`
 *
 * Dates are normalized via [roundToDeciseconds] to absorb sub-second
 * precision differences between server and local storage.
 */
object SyncDiffer {
    /**
     * Normalizes an [Instant] by dividing epoch-millis by 100 and rounding,
     * absorbing sub-second precision differences between server and client.
     */
    fun roundToDeciseconds(instant: Instant): Long =
        instant
            .toEpochMilliseconds()
            .toDouble()
            .div(100.0)
            .roundToLong()

    /**
     * Returns the later of [revisionDate] and [deletedDate], treating
     * soft-delete as a revision for comparison purposes.
     */
    fun effectiveDate(
        revisionDate: Instant,
        deletedDate: Instant?,
    ): Instant = deletedDate?.let(revisionDate::coerceAtLeast) ?: revisionDate

    /**
     * Computes the list of [SyncAction]s needed to reconcile [localItems]
     * with [serverItems].
     *
     * @param dateNormalizer function to normalize timestamps for comparison;
     *   defaults to [roundToDeciseconds].
     * @param currentServiceVersion the current schema version; entities with
     *   an older version are force-updated.
     */
    fun diff(
        localItems: List<LocalItemMeta>,
        serverItems: List<ServerItemMeta>,
        dateNormalizer: (Instant) -> Long = ::roundToDeciseconds,
        currentServiceVersion: Int = BitwardenService.VERSION,
    ): List<SyncAction> {
        val actions = mutableListOf<SyncAction>()

        val existingByRemoteId = mutableMapOf<String, MutableList<LocalItemMeta>>()
        val newLocalItems = mutableListOf<LocalItemMeta>()

        for (item in localItems) {
            val remoteId = item.remoteId
            if (remoteId != null) {
                existingByRemoteId
                    .getOrPut(remoteId) { mutableListOf() }
                    .add(item)
            } else {
                newLocalItems.add(item)
            }
        }

        for (serverItem in serverItems) {
            val serverId = serverItem.id
            val localGroup = existingByRemoteId.remove(serverId)

            val localItem =
                resolveLocalGroup(
                    group = localGroup,
                    dateNormalizer = dateNormalizer,
                    actions = actions,
                )

            if (localItem != null) {
                diffExistingPair(
                    localItem = localItem,
                    serverItem = serverItem,
                    dateNormalizer = dateNormalizer,
                    currentServiceVersion = currentServiceVersion,
                    actions = actions,
                )
            } else {
                actions += SyncAction.InsertLocally(serverId = serverId)
            }
        }

        for ((_, group) in existingByRemoteId) {
            for (item in group) {
                actions += SyncAction.DeleteLocally(localId = item.localId)
            }
        }

        for (item in newLocalItems) {
            diffNewLocalItem(item, dateNormalizer, actions)
        }

        return actions
    }

    private fun resolveLocalGroup(
        group: List<LocalItemMeta>?,
        dateNormalizer: (Instant) -> Long,
        actions: MutableList<SyncAction>,
    ): LocalItemMeta? {
        if (group.isNullOrEmpty()) return null
        if (group.size == 1) return group.first()

        val winner =
            group.maxByOrNull { item ->
                dateNormalizer(
                    effectiveDate(item.revisionDate, item.deletedDate),
                )
            }
        for (item in group) {
            if (item !== winner) {
                actions += SyncAction.DeleteLocally(localId = item.localId)
            }
        }
        return winner
    }

    private fun diffExistingPair(
        localItem: LocalItemMeta,
        serverItem: ServerItemMeta,
        dateNormalizer: (Instant) -> Long,
        currentServiceVersion: Int,
        actions: MutableList<SyncAction>,
    ) {
        val localId = localItem.localId
        val serverId = serverItem.id

        if (localItem.serviceVersion < currentServiceVersion) {
            actions +=
                SyncAction.UpdateLocally(
                    localId = localId,
                    serverId = serverId,
                    force = true,
                )
            return
        }

        val lastSyncedDate =
            effectiveDate(
                revisionDate = localItem.lastSyncedRevisionDate ?: Instant.DISTANT_PAST,
                deletedDate = localItem.lastSyncedDeletedDate,
            ).let(dateNormalizer)

        val localDate =
            effectiveDate(
                revisionDate = localItem.revisionDate,
                deletedDate = localItem.deletedDate,
            ).let(dateNormalizer)

        val serverDate =
            effectiveDate(
                revisionDate = serverItem.revisionDate,
                deletedDate = serverItem.deletedDate,
            ).let(dateNormalizer)

        if (serverDate != lastSyncedDate) {
            if (lastSyncedDate != localDate && localItem.isMergeable) {
                actions +=
                    SyncAction.MergeConflict(
                        localId = localId,
                        serverId = serverId,
                    )
            } else {
                actions +=
                    SyncAction.UpdateLocally(
                        localId = localId,
                        serverId = serverId,
                    )
            }
            return
        }

        val diff = localDate.compareTo(serverDate)
        when {
            diff < 0 -> {
                actions +=
                    SyncAction.UpdateLocally(
                        localId = localId,
                        serverId = serverId,
                    )
            }

            diff > 0 -> {
                if (localItem.isLocallyDeleted) {
                    actions +=
                        SyncAction.DeleteOnServer(
                            localId = localId,
                            serverId = serverId,
                        )
                } else {
                    if (localItem.canRetryError || !localItem.hasError) {
                        actions +=
                            SyncAction.PushToServer(
                                localId = localId,
                                serverId = serverId,
                            )
                    }
                }
            }

            else -> {
                val rawLocalDate = effectiveDate(localItem.revisionDate, localItem.deletedDate)
                val rawServerDate = effectiveDate(serverItem.revisionDate, serverItem.deletedDate)
                val dateRoundingError = rawServerDate != rawLocalDate
                val attachmentIdsDiffer =
                    localItem.attachmentIds != null &&
                        serverItem.attachmentIds != null &&
                        localItem.attachmentIds != serverItem.attachmentIds
                val folderIdDiffers =
                    localItem.folderId != null &&
                        serverItem.folderId != null &&
                        localItem.folderId != serverItem.folderId
                val favoriteDiffers =
                    localItem.favorite != null &&
                        serverItem.favorite != null &&
                        localItem.favorite != serverItem.favorite
                val collectionIdsDiffer =
                    localItem.collectionIds != null &&
                        serverItem.collectionIds != null &&
                        localItem.collectionIds != serverItem.collectionIds

                val canRetryOrHasNoError = localItem.canRetryError || !localItem.hasError
                val serverAttachmentIds = serverItem.attachmentIds
                val hasPendingLocalAttachmentUpload =
                    localItem.pendingLocalAttachmentIds.isNotEmpty()
                val pendingRemoteAttachmentDeletionIds =
                    localItem.pendingRemoteAttachmentDeletionIds
                val pendingRemoteAttachmentDeletionStillOnServer =
                    serverAttachmentIds != null &&
                        pendingRemoteAttachmentDeletionIds
                            .any { it in serverAttachmentIds }
                val pendingRemoteAttachmentDeletionAlreadyApplied =
                    serverAttachmentIds != null &&
                        pendingRemoteAttachmentDeletionIds
                            .any { it !in serverAttachmentIds }
                val hasPendingAttachmentServerWork =
                    hasPendingLocalAttachmentUpload ||
                        pendingRemoteAttachmentDeletionStillOnServer
                val requiresMatchingDateLocalRefresh =
                    dateRoundingError ||
                        localItem.requiresLocalRefreshWhenDatesMatch ||
                        folderIdDiffers ||
                        favoriteDiffers ||
                        collectionIdsDiffer ||
                        pendingRemoteAttachmentDeletionAlreadyApplied ||
                        (
                            attachmentIdsDiffer &&
                                !hasPendingAttachmentServerWork
                        )
                val canPushMatchingDateLocalWork =
                    canRetryOrHasNoError &&
                        (
                            localItem.requiresPushWhenDatesMatch ||
                                hasPendingAttachmentServerWork
                        )
                val canForcePushMatchingDateLocalWork =
                    localItem.requiresForcePushWhenDatesMatch &&
                        canRetryOrHasNoError

                if (requiresMatchingDateLocalRefresh) {
                    actions +=
                        SyncAction.UpdateLocally(
                            localId = localId,
                            serverId = serverId,
                        )
                } else if (canForcePushMatchingDateLocalWork) {
                    actions +=
                        SyncAction.PushToServer(
                            localId = localId,
                            serverId = serverId,
                            force = true,
                        )
                } else if (canPushMatchingDateLocalWork) {
                    actions +=
                        SyncAction.PushToServer(
                            localId = localId,
                            serverId = serverId,
                            force = false,
                        )
                } else if (
                    localItem.hasError
                ) {
                    actions +=
                        SyncAction.UpdateLocally(
                            localId = localId,
                            serverId = serverId,
                        )
                }
            }
        }
    }

    private fun diffNewLocalItem(
        item: LocalItemMeta,
        dateNormalizer: (Instant) -> Long,
        actions: MutableList<SyncAction>,
    ) {
        if (item.isLocallyDeleted) {
            actions += SyncAction.DeleteLocally(localId = item.localId)
        } else {
            if (item.canRetryError || !item.hasError) {
                actions +=
                    SyncAction.PushToServer(
                        localId = item.localId,
                        serverId = null,
                    )
            }
        }
    }
}
