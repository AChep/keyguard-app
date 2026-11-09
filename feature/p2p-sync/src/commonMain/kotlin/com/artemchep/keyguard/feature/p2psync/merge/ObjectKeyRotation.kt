package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.ObjectKey
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import kotlinx.serialization.json.JsonObject

/**
 * When a device writes a new object key, see [ObjectKey].
 *
 * A device writes one once every active device supports [EntryKind.OBJECT_KEY], and the
 * group has no usable key, see [ObjectKey.usable]: it has none, or the device knows of an
 * inactive device the key does not cover. The new key covers every device that is inactive
 * in the roster of the device. A key of a device that is no longer active counts as no key,
 * also a late one that wins by its stamp: an active device replaces it again.
 *
 * A device that knows less than the writer of the key never writes a new one, so devices
 * never replace each other's keys in turn. A device adopts the roster of a peer before its
 * entries, so a device that holds a key knows of every device the key covers. Once the
 * rosters agree, the key that wins was written by a device that knew of every removal.
 */
object ObjectKeyRotation {
    /**
     * Returns the body of the new object key to publish, or `null` if the [current] one
     * stays. The body holds the new key alone, see [ObjectKey.toBody]: no field of the
     * [current] one carries over, so its writer can not pad it until no new key fits.
     *
     * @param current the entry of the object key, [ObjectKey.ENTRY_KEY], or `null` if there
     *   is none.
     * @param activity the activity of the validated roster.
     * @param randomBytes returns that many cryptographically secure random bytes.
     */
    fun next(
        current: SyncEntry?,
        activity: RosterActivity,
        randomBytes: (Int) -> ByteArray,
    ): JsonObject? {
        val stays = ObjectKey.usable(current, activity) != null
        if (stays || !activity.caps.supports(EntryKind.OBJECT_KEY)) return null

        return ObjectKey(key = randomBytes(ObjectKey.KEY_BYTES), covers = activity.inactiveIds.sorted()).toBody()
    }
}
