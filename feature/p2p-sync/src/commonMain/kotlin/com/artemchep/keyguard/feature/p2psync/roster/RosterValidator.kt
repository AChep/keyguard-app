package com.artemchep.keyguard.feature.p2psync.roster

import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.crypto.verifyBase64Url
import com.artemchep.keyguard.feature.p2psync.model.DeviceRecordRules
import com.artemchep.keyguard.feature.p2psync.model.Revocation
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.model.revocationOrder

/**
 * Drops roster records of the sync group [groupId] whose signatures do not verify. Every
 * signature covers the group id, see [RosterSigner].
 *
 * A device record needs a valid self-signature by its device key over the whole record, a
 * label and a platform within [DeviceRecordRules], and a valid admission over its identity
 * by the authority key of its inviter, whose record must be valid too: every valid record
 * leads back to the founder, which admits itself, and whose identity derives [groupId], see
 * [derivedGroupId]. A revocation needs a strictly sorted seal and a valid signature by the
 * authority key of its signer; a device without an authority key may only leave, signed by
 * its device key. A signature is valid only in canonical base64url.
 *
 * Remembers the signatures that verified, of the records and revocations its last check
 * kept, so a roster that repeats them costs no verification. Not thread-safe.
 */
class RosterValidator(
    private val crypto: P2pCrypto,
    private val groupId: String,
) {
    /** The signature checks that passed, see [verify]. */
    private val verified = mutableSetOf<VerifiedSignature>()

    /**
     * Returns the merge of the trusted local roster [local] and a received roster [incoming]
     * without forged records.
     *
     * The records of [incoming] are checked against [local]: a record of a device [local]
     * knows must have its identity, and every admission must lead back to the founder that
     * [local] holds. Of the valid records of one device, the newer one wins, see
     * [RosterCrdt]. So a peer can neither replace the founder nor swap the admission of a
     * device for one it signed itself.
     *
     * A merge that would hold more than [Roster.MAX_DEVICES] device records, or not fit a
     * ROSTER message, see [Roster.fitsMessage], is refused whole: the result is the
     * validated [local] roster. Use [mergeOrNull] to tell a refusal apart.
     *
     * If [local] holds every record and revocation of [incoming], the result is [local] as
     * it is, unchecked: it is trusted, and [incoming] adds nothing.
     */
    fun merge(local: Roster, incoming: Roster): Roster = mergeOrNull(local, incoming) ?: validate(local)

    /**
     * Returns the merge of [local] and [incoming], see [merge], or `null` if it is refused
     * whole. Then [incoming] vouches for nothing: not even its revocations count.
     */
    fun mergeOrNull(local: Roster, incoming: Roster): Roster? {
        if (incoming.founder == local.founder && local.holdsAll(incoming)) return local
        val merged = validRecords(RosterCrdt.union(validate(local), incoming))
        val fits = merged.devices.size <= Roster.MAX_DEVICES && merged.fitsMessage()
        return merged.takeIf { fits }
    }

    /**
     * Returns [roster] without the records whose signatures do not verify, with one record
     * per device: the newer valid one, see [RosterCrdt]. A roster of more than
     * [Roster.MAX_DEVICES] valid device records is refused whole: the result holds no
     * record.
     *
     * The founder record anchors the roster; if the roster holds records of the founder with
     * different identities, which only a forger makes, the newer one that derives [groupId]
     * is the anchor. Merge a received roster with [merge], which anchors it to the trusted
     * one.
     */
    fun validate(roster: Roster): Roster {
        val valid = validRecords(roster)
        return if (valid.devices.size > Roster.MAX_DEVICES) Roster(founder = roster.founder) else valid
    }

    /** `true` if the label, the platform, the size and the depth of [device] are valid, see [DeviceRecordRules]. */
    fun isWellFormed(device: RosterDevice): Boolean =
        DeviceRecordRules.isValidLabel(device.label) &&
            DeviceRecordRules.isValidPlatform(device.platform) &&
            DeviceRecordRules.isWithinLimits(device)

    /** `true` if [device] carries a valid self-signature. */
    fun isSelfSigned(device: RosterDevice): Boolean =
        verify(SignatureKind.SELF, device.signPub, device.canonicalText, device.selfSig) {
            RosterSignatures.selfBytes(groupId, device)
        }

    /** `true` if [device] carries a valid admission by the authority key of [inviter]. */
    fun isAdmitted(device: RosterDevice, inviter: RosterDevice?): Boolean =
        inviter != null &&
            inviter.id == device.addedBy &&
            verify(SignatureKind.ADMISSION, inviter.authorityPub, device.canonicalText, device.admission) {
                RosterSignatures.admissionBytes(groupId, device)
            }

    /**
     * `true` if [revocation] has a strictly sorted seal and carries a valid signature by
     * [revoker]: by its authority key, or, for a device without one, by its device key if
     * it leaves.
     */
    fun isValidRevocation(revocation: Revocation, revoker: RosterDevice?): Boolean {
        val key = when {
            revoker == null || revoker.id != revocation.by -> null
            // The list of the devices it admitted counts only under the authority key,
            // which a stolen device can not use without the master password.
            revoker.authorityPub.isNotEmpty() -> revoker.authorityPub
            revocation.deviceId == revoker.id -> revoker.signPub
            else -> null
        }
        return key != null &&
            revocation.admitted.isStrictlySorted() &&
            verify(SignatureKind.REVOCATION, key, revocation.canonicalText, revocation.sig) {
                RosterSignatures.revocationBytes(groupId, revocation)
            }
    }

    /**
     * [roster] without the records whose signatures do not verify, however many are left.
     * Forgets the checks of every other record and revocation.
     */
    private fun validRecords(roster: Roster): Roster {
        val selected = admittedRecords(roster)
        val revoked = roster.revoked.filter { revocation ->
            DeviceRecordRules.isWithinLimits(revocation) && isValidRevocation(revocation, selected[revocation.by])
        }
        val valid = Roster(
            founder = roster.founder,
            devices = roster.devices.filter { selected[it.id] == it },
            revoked = revoked.withOneLeavePerDevice(selected),
        )
        val kept = (valid.devices.map { it.canonicalText } + valid.revoked.map { it.canonicalText }).toSet()
        verified.retainAll { it.signed in kept }
        return valid
    }

    /**
     * Keeps one leave, the first, of each device without an authority key. Such a device
     * signs its leave with its device key, so a stolen one could sign any number of them and
     * grow the roster past what a ROSTER message holds; it admitted no one, so its seal
     * means nothing, and the first leave says when it left.
     */
    private fun List<Revocation>.withOneLeavePerDevice(selected: Map<String, RosterDevice>): List<Revocation> {
        fun isLeave(revocation: Revocation) = selected[revocation.by]?.authorityPub?.isEmpty() == true
        val first = filter(::isLeave)
            .groupBy { it.by }
            .values
            .mapTo(mutableSetOf()) { it.minWith(revocationOrder) }
        return filter { !isLeave(it) || it in first }
    }

    /**
     * The valid record of each device, by id: walks the admissions from the founder, so each
     * record is admitted by the record of its inviter chosen before it.
     */
    private fun admittedRecords(roster: Roster): Map<String, RosterDevice> {
        val signed = roster.devices.distinct().filter { isWellFormed(it) && isSelfSigned(it) }
        val founder = signed
            .filter { it.id == roster.founder && it.addedBy == it.id && isAdmitted(it, it) }
            .filter { it.derivedGroupId(crypto::sha256) == groupId }
            .maxWithOrNull(RosterCrdt.recordOrder)
        return walkAdmissions(founder, signed) { inviter, device -> isAdmitted(device, inviter) }
    }

    /**
     * `true` if [signature], of the [kind] over the object with the canonical text [signed],
     * verifies by [publicKey]; [bytes] are what it covers. A check that passed before passes
     * without a verification.
     */
    private fun verify(
        kind: SignatureKind,
        publicKey: String,
        signed: String,
        signature: String,
        bytes: () -> ByteArray,
    ): Boolean {
        val check = VerifiedSignature(kind, publicKey, signed)
        if (check in verified) return true
        // A device without an authority key signs nothing with it.
        val valid = crypto.verifyBase64Url(P2pEncoding.base64UrlOrNull(publicKey), bytes(), signature)
        if (valid) verified += check
        return valid
    }

    private enum class SignatureKind {
        SELF,
        ADMISSION,
        REVOCATION,
    }

    /**
     * A signature check that passed: of the [kind] by [publicKey] over the object with the
     * canonical text [signed], its signatures included.
     */
    private data class VerifiedSignature(
        val kind: SignatureKind,
        val publicKey: String,
        val signed: String,
    )
}

/** `true` if every record and revocation of [other] is one of this roster. */
private fun Roster.holdsAll(other: Roster): Boolean =
    devices.toSet().containsAll(other.devices) && revoked.toSet().containsAll(other.revoked)

/** `true` if each string comes before the next one: sorted, without duplicates. */
internal fun List<String>.isStrictlySorted(): Boolean = zipWithNext().all { (a, b) -> a < b }
