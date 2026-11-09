package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.provider.keyguard.P2pGroupBlobs
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxDownload
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The blob object each sync group could not finish fetching from the relay, see
 * [KeyguardObjectMailbox]: its next fetch resumes from the bytes received. An Android
 * background sync ends its session after a short window, so a large object on a slow link
 * arrives over several windows.
 *
 * It lives as long as the vault session, in memory only: it outlives the session managers,
 * and is gone once the vault locks. It holds one download per group at most, which is at
 * most as large as the largest blob object.
 */
class KeyguardObjectDownloads {
    private val lock = SynchronizedObject()

    /** The download held for each group, by group id. */
    private val held = mutableMapOf<String, RelayMailboxDownload>()

    /**
     * Takes out the download of the object [item] of the group [groupId], or a new one: a
     * download of another object of the group is dropped.
     */
    fun take(groupId: String, item: String): RelayMailboxDownload = synchronized(lock) {
        held.remove(groupId)?.takeIf { it.item == item }
    } ?: RelayMailboxDownload(item)

    /**
     * The item of the download the group [groupId] holds, if any: its fetch goes first, see
     * [P2pGroupBlobs.claimNext].
     */
    fun heldItem(groupId: String): String? = synchronized(lock) { held[groupId]?.item }

    /**
     * Keeps the [download] for the next fetch of its object by the group [groupId], in place
     * of the one the group holds, if it can resume.
     */
    fun keep(groupId: String, download: RelayMailboxDownload) {
        if (download.isResumable) synchronized(lock) { held[groupId] = download }
    }
}
