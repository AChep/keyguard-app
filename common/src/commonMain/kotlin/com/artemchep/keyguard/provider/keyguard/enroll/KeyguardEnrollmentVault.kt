package com.artemchep.keyguard.provider.keyguard.enroll

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.P2pDevice
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentBundle
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.feature.p2psync.roster.RosterValidator
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKey
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKeys
import com.artemchep.keyguard.provider.keyguard.KeyguardDeviceFactory
import com.artemchep.keyguard.provider.keyguard.P2pGroupHost
import com.artemchep.keyguard.provider.keyguard.P2pGroupHosts
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import com.artemchep.keyguard.provider.keyguard.sync.LocalRoster
import com.artemchep.keyguard.provider.keyguard.sync.requireLocalRoster
import kotlin.time.Clock
import kotlin.time.Instant

/** The admission was refused: the record of the joining device does not fit the group. */
class KeyguardAdmissionException(message: String) : IllegalArgumentException(message)

/**
 * What an enrollment reads and writes on this device: the sync group it shares, or joins.
 * What depends on the kind of the group goes through its host, see [P2pGroupHosts].
 */
class KeyguardEnrollmentVault(
    private val db: VaultDatabaseManager,
    private val crypto: P2pCrypto,
    private val deviceFactory: KeyguardDeviceFactory,
    private val authorityKeys: KeyguardAuthorityKeys,
    private val hosts: P2pGroupHosts,
    private val now: () -> Instant = { Clock.System.now() },
) {
    companion object {
        private const val TAG = "KeyguardEnrollment"
    }

    /** The group [groupId], its membership, and the roster record of this device in it. */
    class Member(
        val groupId: P2pGroupId,
        val membership: P2pMembership,
        val record: RosterDevice,
    )

    /**
     * The admission of a device, signed but not stored: the [bundle] that hands the group
     * over, and the [record] it admits. See [admit] and [saveAdmission].
     */
    class Admission(
        val bundle: EnrollmentBundle,
        val record: RosterDevice,
    )

    /**
     * Returns the group [groupId] as a member shares it.
     *
     * @throws IllegalStateException if this device is not active in the group.
     * @throws IllegalArgumentException if there is no such group.
     */
    suspend fun member(groupId: P2pGroupId): Member = db
        .mutate(TAG) { database ->
            val roster = database.requireLocalRoster(groupId, crypto)
            val record = checkNotNull(roster.self) {
                "This device is not an active device of the group."
            }
            Member(groupId, roster.membership, record)
        }
        .bind()

    /** Checks that this device may join a group of the [scope], see [P2pGroupHost.checkJoinable]. */
    suspend fun checkJoinable(scope: SyncScope) = hosts
        .forScope(scope)
        .checkJoinable()

    /**
     * Creates the identity of a new device named [label] that joins a group, with an
     * authority key sealed under the master [password].
     */
    suspend fun newDevice(label: String, password: String): P2pDevice = authorityKeys
        .create(password)
        .bind()
        .use { authority ->
            deviceFactory
                .create(label = label, authority = authority.authority)
                .bind()
        }

    /**
     * Opens the authority key of the [member] with the master [password], so it can admit
     * a device; close it once done.
     *
     * @throws KeyguardPairingException if the device has no authority key, or the
     *   password does not open it.
     */
    suspend fun openAuthority(member: Member, password: String): KeyguardAuthorityKey {
        val authority = member.membership.device.authority
            ?.let { authorityKeys.open(it, password).bind() }
        return authority ?: throw KeyguardPairingException(
            error = KeyguardPairingError.FAILED,
            message = "The authority key of this device does not open.",
        )
    }

    /**
     * Admits the [joiner] to the group of the [member]: signs the identity of its record
     * with the open [authority] key of the member and returns the bundle that hands the
     * group over, with the roster that admits it. Nothing is stored: [saveAdmission] adds
     * the record to the roster once the joiner acknowledged the bundle. The joiner may
     * rename itself later without a new admission.
     *
     * @throws KeyguardAdmissionException if the record names another inviter, is not
     * new, is not signed by the joiner for the group, has no authority key or an invalid
     * name, the device was in the group before, or the group is full.
     */
    suspend fun admit(
        member: Member,
        joiner: RosterDevice,
        authority: KeyguardAuthorityKey,
    ): Admission {
        val groupId = member.groupId
        val membership = member.membership
        // Before the roster changes: the roster only grows, so the
        // bundle lists every device an entry came from.
        val entries = hosts
            .forGroup(groupId)
            .buildLocalState(groupId)
        val roster = db
            .mutate(TAG) { database ->
                KeyguardSyncStore(database, groupId.id).loadValidated(membership, crypto)
            }
            .bind()
        val problem = admissionProblem(membership.groupId, roster, joiner)
        if (problem != null) throw KeyguardAdmissionException(problem)

        val admitted = RosterSigner(crypto, membership.groupId).admit(joiner, authority.seed)
        val bundle = EnrollmentBundle(
            group = membership.groupId,
            groupKey = membership.groupKeyBase64,
            roster = roster.stored.withDevice(admitted),
            entries = entries,
            relay = membership.network.relayUrl,
            ice = membership.network.iceUrl,
        )
        return Admission(bundle, admitted)
    }

    /**
     * Stores the [admission] of [admit] in the roster of the group of the [member], once the
     * joiner acknowledged the bundle. The roster may have changed since: the admission is
     * checked against it again.
     *
     * @throws KeyguardAdmissionException if the device can no longer join, see [admit]. The
     *   joiner holds the bundle by then, but no device of the group learns it.
     */
    suspend fun saveAdmission(
        member: Member,
        admission: Admission,
    ) {
        val membership = member.membership
        db
            .mutate(TAG) { database ->
                val store = KeyguardSyncStore(database, member.groupId.id)
                val roster = store.loadValidated(membership, crypto)
                val problem = admissionProblem(membership.groupId, roster, admission.record)
                if (problem != null) throw KeyguardAdmissionException(problem)

                store.saveRoster(roster.stored.withDevice(admission.record))
            }
            .bind()
    }

    private fun Roster.withDevice(device: RosterDevice): Roster = copy(devices = devices + device).normalized()

    /** Returns why [joiner] can not join the group [groupId] with the [roster], if it can not. */
    private fun admissionProblem(
        groupId: String,
        roster: LocalRoster,
        joiner: RosterDevice,
    ): String? {
        val self = roster.self
        val stored = roster.stored
        val known = stored.devices.any { it.id == joiner.id } ||
            stored.revoked.any { it.deviceId == joiner.id }
        // How far ahead of this device's clock an admission stamp may be.
        val latest = now().toEpochMilliseconds() + HlcClock.MAX_FUTURE_DRIFT_MS
        val validator = RosterValidator(crypto, groupId)
        return when {
            self == null -> "This device is not active in the group."
            known -> "The device was in the group before."
            // A roster of more records is refused by every device.
            roster.validated.devices.size >= Roster.MAX_DEVICES -> "The group is full."
            joiner.addedBy != self.id -> "The device names another inviter."
            // Admitted before this device was, it would never become active.
            joiner.addedAt <= self.addedAt -> "The clock of the device is behind."
            joiner.addedAt.wall > latest -> "The clock of the device is ahead."
            // A new record was never renamed.
            joiner.updatedAt != joiner.addedAt -> "The record of the device is not new."
            joiner.authorityPub.isEmpty() -> "The device has no authority key."
            !validator.isWellFormed(joiner) -> "The name or the platform of the device is not valid."
            !validator.isSelfSigned(joiner) -> "The record of the device is not signed by it for the group."
            else -> null
        }
    }

    /** Tells the other devices of [groupId] about a new one, through a sync pass. */
    suspend fun announce(groupId: P2pGroupId) {
        hosts(groupId).bind()
    }

    /**
     * Stores the group of the [scope] of the [bundle] as the [device], with the [network]
     * settings, see [P2pGroupHost.import].
     */
    suspend fun import(
        bundle: EnrollmentBundle,
        device: P2pDevice,
        network: P2pNetwork,
        scope: SyncScope,
    ): P2pGroupId = hosts
        .forScope(scope)
        .import(bundle, device, network)
}
