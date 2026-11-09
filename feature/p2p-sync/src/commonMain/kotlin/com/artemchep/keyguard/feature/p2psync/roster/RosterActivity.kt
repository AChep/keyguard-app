package com.artemchep.keyguard.feature.p2psync.roster

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.model.DeviceCaps
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.Revocation
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice

/**
 * What every active device of a group supports, see [DeviceCaps]: the group writes nothing
 * one of them can not read.
 *
 * @property rules the effective rules version: the lowest one of the active devices.
 * @property kinds the wire names of the entry kinds every active device supports.
 * @property features the features every active device turned on, see [DeviceCaps.features].
 */
data class GroupCaps(
    val rules: Int,
    val kinds: Set<String>,
    val features: Set<String> = emptySet(),
) {
    /** `true` if every active device supports the [kind]: a device may write entries of it. */
    fun supports(kind: EntryKind): Boolean = kind.wireName in kinds

    companion object {
        /**
         * Returns what every one of the [devices] supports; what this build supports, see
         * [DeviceCaps.CURRENT], if there are none.
         */
        fun of(devices: Collection<RosterDevice>): GroupCaps {
            val caps = devices.map { it.caps }.ifEmpty { listOf(DeviceCaps.CURRENT) }
            return GroupCaps(
                rules = caps.minOf { it.rules },
                kinds = caps
                    .map { it.kinds.toSet() }
                    .reduce { common, kinds -> common intersect kinds },
                features = caps
                    .map { it.features.toSet() }
                    .reduce { common, features -> common intersect features },
            )
        }
    }
}

/**
 * Which devices of a [roster] are admitted and which are revoked.
 *
 * A stamp says whatever its signer wants, and a revoked device keeps its signing key. So
 * neither rule below depends on the order of stamps:
 *
 * - The founder admits itself. Any other device is admitted if its inviter is admitted and
 *   joined before it. Once the inviter is revoked, a device it admitted stays admitted only
 *   if every revocation of the inviter lists the hash of its identity in
 *   [Revocation.admitted], see [identityHash]. A revoked device can not bring anyone in,
 *   however it stamps the admission.
 * - A device is revoked once a member signs its revocation: a device whose chain of
 *   admissions leads back to the founder. Revocations are sticky. A revoked device can
 *   still revoke others, but it never gets back in.
 *
 * This works on the structure only: drop forged records with
 * [RosterValidator.validate] first.
 *
 * @param hasher the SHA-256 of the identity hashes, see [identityHash].
 */
class RosterActivity(
    roster: Roster,
    hasher: (ByteArray) -> ByteArray,
) {
    /** The devices whose chain of admissions leads back to the founder, by id. */
    private val members: Map<String, RosterDevice>

    /** The revocations of each device signed by a member, by revoked id. */
    private val revocations: Map<String, List<Revocation>>

    /** The ids of the devices that are admitted and not revoked. */
    val activeIds: Set<String>

    /** The ids of the other devices of the roster: revoked, evicted by a seal, or never admitted. */
    val inactiveIds: Set<String>

    /** What every active device supports. */
    val caps: GroupCaps

    init {
        val records = roster.devices.distinctBy { it.id }
        val founder = records.firstOrNull { it.id == roster.founder }?.takeIf { it.addedBy == it.id }
        members = walkAdmissions(founder, records) { _, _ -> true }
        revocations = roster.revoked
            .filter { it.by in members }
            .groupBy { it.deviceId }
        val admitted = walkAdmissions(founder, records) { inviter, device ->
            val seals = revocations[inviter.id].orEmpty()
            // The seal names whole identities: a new record with the key of a sealed device
            // is a device the seal never kept.
            val identity = if (seals.isEmpty()) null else device.identityHash(hasher)
            inviter.addedAt < device.addedAt && seals.all { identity in it.admitted }
        }
        activeIds = admitted.keys - revocations.keys
        inactiveIds = records.mapTo(mutableSetOf()) { it.id } - activeIds
        caps = GroupCaps.of(admitted.filterKeys { it in activeIds }.values)
    }

    /** `true` if the device is admitted and not revoked. */
    fun isActive(deviceId: String): Boolean = deviceId in activeIds

    /**
     * `true` if the device is a member and was admitted by the moment [at], or at most
     * [clockSkewMs] after it: its first stamps come from its own clock, which can run behind
     * the clock of its inviter.
     *
     * Unlike [isActive] it ignores revocations and seals, so it never turns `false` as the
     * roster grows.
     */
    fun hasJoinedBy(deviceId: String, at: Hlc, clockSkewMs: Long = 0L): Boolean {
        val device = members[deviceId] ?: return false
        return at.wall >= device.addedAt.wall - clockSkewMs
    }

    /** When the device was revoked, the earliest stamp of its revocations, or `null` if it is not. */
    fun revokedAt(deviceId: String): Hlc? = revocations[deviceId]?.minOf { it.at }

    /**
     * `true` if other devices removed the device, it did not leave, and none of them is
     * active: a removed device that still knows the master password may have removed it,
     * itself or through a device it added. It reads no stamps, which their signer picks, so a
     * removal by a device that was removed later looks the same. It only words a warning; it
     * never changes who is active.
     */
    fun wasRevokedByRevoked(deviceId: String): Boolean {
        val removers = revocations[deviceId].orEmpty().map { it.by }.toSet()
        val others = removers - deviceId
        return deviceId !in removers && others.isNotEmpty() && others.none { it in activeIds }
    }
}

/** The devices of this roster that are admitted and not revoked; [hasher] is SHA-256, see [RosterActivity]. */
fun Roster.activeDevices(hasher: (ByteArray) -> ByteArray): List<RosterDevice> {
    val activity = RosterActivity(this, hasher)
    return devices.filter { activity.isActive(it.id) }.distinctBy { it.id }
}
