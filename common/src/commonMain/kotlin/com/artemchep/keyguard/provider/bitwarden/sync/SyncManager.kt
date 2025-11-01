package com.artemchep.keyguard.provider.bitwarden.sync

import com.artemchep.keyguard.common.util.millis
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.canRetry
import kotlin.time.Instant
import kotlin.math.roundToLong

class SyncManager<Local : BitwardenService.Has<Local>, Remote : Any>(
    private val local: LensLocal<Local>,
    private val remote: Lens<Remote>,
    private val getDateMillis: (Instant) -> Long = ::roundToMillis,
) {
    companion object {
        // FIXME: After we create something, the date is
        //    --
        //    2022-09-21T14:04:33.1819975Z
        //    --
        //  but after we get the same entry using the sync
        //  API the revision date is some-why rounded to:
        //    --
        //    2022-09-21T14:04:33.1833333Z
        //    --
        //  for now we round the revision date to
        //    --
        //    2022-09-21T14:04:33.18
        //    --
        fun roundToMillis(instant: Instant) =
            instant.millis.toDouble().div(100.0).roundToLong()

        fun floorToSeconds(instant: Instant) =
            instant.millis.toDouble().div(1000.0).roundToLong()
    }

    data class Lens<T>(
        val getId: (T) -> String,
        val getRevisionDate: (T) -> Instant,
        val getDeletedDate: (T) -> Instant? = { null },
    )

    class LensLocal<T>(
        val getLocalId: (T) -> String,
        val getLocalRevisionDate: (T) -> Instant,
        val getLocalDeletedDate: (T) -> Instant? = { null },
        val getMergeable: (T) -> Boolean = { false },
    )

    data class Df<Local : Any, Remote : Any>(
        // Delete items
        val remoteDeletedCipherIds: List<Ite<Local, Remote>>,
        val localDeletedCipherIds: List<Ite<Local, Remote?>>,
        // Put items
        val remotePutCipher: List<Ite<Local, Remote?>>,
        val localPutCipher: List<Ite<Local?, Remote>>,
        // Merge items
        val mergeCipher: List<Ite<Local, Remote>>,
    ) {
        data class Ite<Local, Remote>(
            val local: Local,
            val remote: Remote,
            val force: Boolean = false,
        )
    }

    private fun getDate(model: Remote) = getDate(
        revisionDate = remote.getRevisionDate(model),
        deletedDate = remote.getDeletedDate(model),
    )

    private fun getDate(model: Local) = getDate(
        revisionDate = local.getLocalRevisionDate(model),
        deletedDate = local.getLocalDeletedDate(model),
    )

    private fun getDate(
        revisionDate: Instant,
        deletedDate: Instant?,
    ) = deletedDate?.let(revisionDate::coerceAtLeast)
        ?: revisionDate

    /**
     * Calculates the difference between local and remote
     * representations of a server, allowing you to easily sync
     * them with each other.
     */
    fun df(
        localItems: Collection<Local>,
        remoteItems: Collection<Remote>,
        shouldOverwriteLocal: (Local, Remote) -> Boolean,
        shouldOverwriteRemote: (Local, Remote) -> Boolean,
    ): Df<Local, Remote> {
        // Delete items
        val remoteDeletedCipherIds = mutableListOf<Df.Ite<Local, Remote>>()
        val localDeletedCipherIds = mutableListOf<Df.Ite<Local, Remote?>>()
        // Put items
        val remotePutCipher = mutableListOf<Df.Ite<Local, Remote?>>()
        val localPutCipher = mutableListOf<Df.Ite<Local?, Remote>>()
        // Merge items
        val mergeCipher = mutableListOf<Df.Ite<Local, Remote>>()

        val localItemsGrouped = localItems
            .groupBy {
                it.service.remote != null
            }
        val localItemsNew = localItemsGrouped
            .getOrDefault(false, emptyList()) // no remote
        val localItemsExistingByRemoteId = localItemsGrouped
            .getOrDefault(true, emptyList()) // remote
            .groupBy { localItem ->
                val remoteId = requireNotNull(localItem.service.remote?.id)
                remoteId
            }
            .toMutableMap()

        remoteItems.forEach { remoteItem ->
            val remoteItemId = remote.getId(remoteItem)
            val localItemGroup = localItemsExistingByRemoteId
                .remove(remoteItemId)
            val localItem = localItemGroup?.let { group ->
                // If there's only one item in the group, then
                // we can safely use it.
                if (group.size <= 1) {
                    return@let group.firstOrNull()
                }

                // Try to auto-fix the conflict by using the
                // item with the freshest revision date and
                // removing the old item.
                val localItem = group
                    .maxByOrNull { localItem ->
                        getDate(localItem)
                    }
                // Remove all other items in the group.
                group.forEach { item ->
                    if (item === localItem) {
                        return@forEach
                    }
                    localDeletedCipherIds += Df.Ite(item, null)
                }
                localItem
            }
            if (localItem != null) {
                // TODO: Replace it with a migration mechanism
                if (localItem.service.version < BitwardenService.VERSION) {
                    localPutCipher += Df.Ite(localItem, remoteItem)
                    return@forEach
                }

                val localRemoteRevDate = kotlin.run {
                    val date = getDate(
                        revisionDate = localItem.service.remote?.revisionDate
                            ?: Instant.DISTANT_PAST,
                        deletedDate = localItem.service.remote?.deletedDate,
                    )
                    getDateMillis(date)
                }
                val localRevDate = getDate(localItem)
                    .let(getDateMillis)
                // If the local item has outdated remote revision, then it must
                // be updated and any of its changes are going to be discarded
                // or merged.
                //
                // Why:
                // This is needed to resolve a conflict where you edit one item
                // on multiple devices simultaneously.
                val remoteRevDate = getDate(remoteItem)
                    .let(getDateMillis)
                if (remoteRevDate != localRemoteRevDate) {
                    if (localRemoteRevDate != localRevDate && local.getMergeable(localItem)) {
                        mergeCipher += Df.Ite(localItem, remoteItem)
                    } else {
                        localPutCipher += Df.Ite(localItem, remoteItem)
                    }
                    return@forEach
                }

                val diff = localRevDate.compareTo(remoteRevDate)
                when {
                    diff < 0 -> {
                        localPutCipher += Df.Ite(localItem, remoteItem)
                    }

                    diff > 0 -> {
                        if (localItem.service.deleted) {
                            remoteDeletedCipherIds += Df.Ite(localItem, remoteItem)
                        } else {
                            val error = localItem.service.error
                            val revisionDate = getDate(localItem)
                            if (error?.canRetry(revisionDate) != false) {
                                remotePutCipher += Df.Ite(localItem, remoteItem)
                            }
                        }
                    }

                    else -> {
                        // Date rounding error can happen because Bitwarden rounds the
                        // revision date. We can not safely resolve this issue, so just
                        // fall back to the remote item.
                        val dateRoundingError = getDate(remoteItem) != getDate(localItem)
                        if (dateRoundingError || localItem.service.error != null) {
                            localPutCipher += Df.Ite(localItem, remoteItem)
                        } else {
                            val overwriteLocal = shouldOverwriteLocal(localItem, remoteItem)
                            if (overwriteLocal) {
                                localPutCipher += Df.Ite(localItem, remoteItem)
                            } else {
                                val overwriteRemote = shouldOverwriteRemote(localItem, remoteItem)
                                if (overwriteRemote) {
                                    // We have to force that in, because the revision
                                    // date has not been changed.
                                    remotePutCipher += Df.Ite(localItem, remoteItem, force = true)
                                } else {
                                    // Up to date.
                                }
                            }
                        }
                    }
                }
            } else {
                localPutCipher += Df.Ite(null, remoteItem)
            }
        }

        // The item has remote id, but the remote items
        // do not have it -- therefore it has deleted on remote.
        localDeletedCipherIds += localItemsExistingByRemoteId
            .asSequence()
            .flatMap { it.value }
            .map { item ->
                Df.Ite(item, null)
            }

        //
        // Handle new items
        //

        localItemsNew.forEach { localItem ->
            if (localItem.service.deleted) {
                localDeletedCipherIds += Df.Ite(localItem, null)
            } else {
                val error = localItem.service.error
                val revisionDate = getDate(localItem)
                if (error?.canRetry(revisionDate) != false) {
                    remotePutCipher += Df.Ite(localItem, null)
                }
            }
        }

        return Df(
            remoteDeletedCipherIds = remoteDeletedCipherIds,
            localDeletedCipherIds = localDeletedCipherIds,
            remotePutCipher = remotePutCipher,
            localPutCipher = localPutCipher,
            mergeCipher = mergeCipher,
        )
    }
}
