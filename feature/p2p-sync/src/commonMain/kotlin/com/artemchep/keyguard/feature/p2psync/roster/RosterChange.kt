package com.artemchep.keyguard.feature.p2psync.roster

import com.artemchep.keyguard.feature.p2psync.model.Roster

/** A change of who is in a sync group. */
sealed interface RosterChange {
    /** The device that changed. */
    val deviceId: String

    /** The device that made the change, if any. */
    val by: String?

    /** The device [deviceId] was admitted by the device [by]. */
    data class Added(
        override val deviceId: String,
        override val by: String,
    ) : RosterChange

    /**
     * The device [deviceId] was removed by the device [by]: by itself if it left, or by an
     * active device. A device that is not active is never named: its stamps and its label are
     * its own to pick. [by] is `null` if no active device removed it: if [byRemoved], only
     * devices that are not active did; else it went with the device that admitted it, see
     * [com.artemchep.keyguard.feature.p2psync.model.Revocation.admitted].
     *
     * @property byRemoved `true` if only devices that are not active removed it, see
     *   [RosterActivity.wasRevokedByRevoked].
     */
    data class Removed(
        override val deviceId: String,
        override val by: String?,
        val byRemoved: Boolean = false,
    ) : RosterChange
}

/**
 * Returns how the group changed from the roster [before] to the roster [after], both
 * validated, see [RosterValidator.validate]: the devices that became active and the ones
 * that are active no more, sorted by device id. The changes the device [selfId] made itself
 * are left out. [hasher] is SHA-256, see [RosterActivity].
 */
fun rosterChanges(
    before: Roster,
    after: Roster,
    selfId: String,
    hasher: (ByteArray) -> ByteArray,
): List<RosterChange> {
    val was = RosterActivity(before, hasher).activeIds
    val activity = RosterActivity(after, hasher)
    val now = activity.activeIds
    val added = (now - was).mapNotNull { id ->
        val record = after.devices.firstOrNull { it.id == id }
        record?.let { RosterChange.Added(deviceId = id, by = it.addedBy) }
    }
    val removed = (was - now).map { id ->
        val byRemoved = activity.wasRevokedByRevoked(id)
        val by = if (byRemoved) null else removerOf(after, activity, id, selfId)
        RosterChange.Removed(deviceId = id, by = by, byRemoved = byRemoved)
    }
    return (added + removed)
        .filter { it.by != selfId }
        .sortedBy { it.deviceId }
}

/**
 * The device to name as the one that removed [deviceId]: this device [selfId] if it did, the
 * device itself if it left, or else the active device of the lowest id that removed it.
 * `null` if no active device removed it.
 */
private fun removerOf(
    after: Roster,
    activity: RosterActivity,
    deviceId: String,
    selfId: String,
): String? {
    val removers = after.revoked
        .filter { it.deviceId == deviceId }
        .map { it.by }
        .toSet()
    return when {
        selfId in removers -> selfId
        deviceId in removers -> deviceId
        else -> removers.filter(activity::isActive).minOrNull()
    }
}
