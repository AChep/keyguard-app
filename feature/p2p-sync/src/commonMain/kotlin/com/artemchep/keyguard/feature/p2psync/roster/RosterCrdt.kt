package com.artemchep.keyguard.feature.p2psync.roster

import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice

/**
 * The roster as a state-based CRDT: per device id the record the device changed last, and
 * a union of revocations. No device and no revocation is ever removed, so revocations are
 * sticky. For rosters that agree on the identity of every device, the merge is
 * commutative, associative and idempotent.
 *
 * Merge a received roster with [RosterValidator.merge] instead: a forged record that looks
 * newer would hide the valid one.
 */
object RosterCrdt {
    /**
     * Of two records of one device, the newer one by [RosterDevice.updatedAt] wins; of equal
     * stamps, the one of the greater canonical text, as signed.
     */
    internal val recordOrder: Comparator<RosterDevice> =
        compareBy<RosterDevice>({ it.updatedAt }, { it.canonicalText })

    /**
     * Returns the union of the local roster [a] and the received roster [b], two rosters of
     * the same group, with the newer record of each device.
     */
    fun merge(a: Roster, b: Roster): Roster {
        val union = union(a, b)
        // Equal stamps of one id only happen if someone forged one; keep the same one everywhere.
        val devices = union.devices
            .groupBy { it.id }
            .map { (_, records) -> records.maxWith(recordOrder) }
        return union.copy(devices = devices).normalized()
    }

    /**
     * Returns every record and revocation of the local roster [a] and the received roster
     * [b], two rosters of the same group, several records of one device included.
     *
     * The identity of a device never changes, so a record of [b] with another identity than
     * the one [a] holds is dropped: a member could sign such a record with keys of its own
     * and take over the device, or the founder.
     */
    fun union(a: Roster, b: Roster): Roster {
        require(a.founder == b.founder) { "Can not merge rosters of different groups" }
        val known = a.devices.groupBy { it.id }
        val received = b.devices.filter { device ->
            known[device.id].orEmpty().all { RosterSignatures.sameIdentity(it, device) }
        }
        return Roster(
            founder = a.founder,
            devices = a.devices + received,
            revoked = a.revoked + b.revoked,
        ).normalized()
    }
}
