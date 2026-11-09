package com.artemchep.keyguard.feature.p2psync.roster

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.crypto.signToBase64Url
import com.artemchep.keyguard.feature.p2psync.crypto.signedBytes
import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import com.artemchep.keyguard.feature.p2psync.model.DeviceCaps
import com.artemchep.keyguard.feature.p2psync.model.Revocation
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * Signs roster records of the sync group [groupId] with Ed25519.
 *
 * Each signature covers its object as it is sent, without its own signature fields, with
 * the group id as context, see [RosterSignatures]: so a field a newer build adds is signed
 * too, and no signature moves to another group.
 *
 * A device record is signed twice. The admission, by the authority key of the inviter,
 * covers the identity of the device: the record without its mutable `label`, `updatedAt`
 * and `caps`, and without its signatures. The self-signature, by the device key, covers the
 * whole record but its signatures, so the device can rename itself without its inviter.
 * Each signature is domain separated, so neither can be replayed as the other.
 *
 * A revocation covers the revoked device, its stamp, its signer and the seal of the devices
 * the revoked device admitted, see [Revocation.admitted]. The authority key of its signer
 * signs it; the device key signs only the leaving of a device without an authority key.
 */
class RosterSigner(
    private val crypto: P2pCrypto,
    private val groupId: String,
) {
    /** Returns [device] signed by its own Ed25519 [signPrivateKey]. */
    fun selfSign(device: RosterDevice, signPrivateKey: ByteArray): RosterDevice =
        device.copy(selfSig = sign(signPrivateKey, RosterSignatures.selfBytes(groupId, device)))

    /** Returns [device] admitted by the inviter, whose authority key is [inviterAuthorityPrivateKey]. */
    fun admit(device: RosterDevice, inviterAuthorityPrivateKey: ByteArray): RosterDevice =
        device.copy(admission = sign(inviterAuthorityPrivateKey, RosterSignatures.admissionBytes(groupId, device)))

    /**
     * Returns the record of [device] with its mutable fields, the [label] and the [caps],
     * as of [updatedAt], which must be newer than its current [RosterDevice.updatedAt],
     * signed by the device's own [signPrivateKey]. The admission stays valid.
     */
    fun update(
        device: RosterDevice,
        updatedAt: Hlc,
        signPrivateKey: ByteArray,
        label: String = device.label,
        caps: DeviceCaps = device.caps,
    ): RosterDevice {
        require(updatedAt > device.updatedAt) { "An updated record must be newer than the current one" }
        return selfSign(device.copy(label = label, caps = caps, updatedAt = updatedAt), signPrivateKey)
    }

    /**
     * Returns the removal of another device, [deviceId], by the device [by] at [at], signed
     * with the authority key of [by], [authorityPrivateKey]. It seals the devices that
     * [deviceId] admitted, see [Revocation.admitted]: the ones in [kept], by default every
     * one the validated [roster] of [by] holds. Any other device it admitted, before or after,
     * never counts.
     */
    // The revocation, and what it seals.
    @Suppress("LongParameterList")
    fun revoke(
        roster: Roster,
        deviceId: String,
        at: Hlc,
        by: String,
        authorityPrivateKey: ByteArray,
        kept: (RosterDevice) -> Boolean = { true },
    ): Revocation {
        require(deviceId != by) { "A device leaves the group with leave()" }
        val admitted = roster.admittedBy(deviceId, crypto::sha256, kept)
        val revocation = Revocation(deviceId = deviceId, at = at, by = by, admitted = admitted)
        return sign(revocation, authorityPrivateKey)
    }

    /**
     * Returns the leaving of the device [deviceId] at [at], signed with its own authority
     * key, or with its device key if it has none: [privateKey]. It seals the devices it
     * admitted that its validated [roster] holds, so its key admits no one once it is gone.
     * The seal decides which devices stay, so only the authority key may sign it.
     */
    fun leave(
        roster: Roster,
        deviceId: String,
        at: Hlc,
        privateKey: ByteArray,
    ): Revocation {
        val admitted = roster.admittedBy(deviceId, crypto::sha256)
        val revocation = Revocation(deviceId = deviceId, at = at, by = deviceId, admitted = admitted)
        return sign(revocation, privateKey)
    }

    private fun sign(revocation: Revocation, privateKey: ByteArray): Revocation =
        revocation.copy(sig = sign(privateKey, RosterSignatures.revocationBytes(groupId, revocation)))

    private fun sign(privateKey: ByteArray, bytes: ByteArray): String = crypto.signToBase64Url(privateKey, bytes)
}

/**
 * The exact bytes roster signatures cover: the domain, and the canonical JSON of the object
 * as sent without its signature fields, with the group id as context, see [signedBytes].
 */
internal object RosterSignatures {
    private const val SELF_DOMAIN = "keyguard-roster-self/v1"
    private const val ADMISSION_DOMAIN = "keyguard-roster-admission/v1"
    private const val REVOCATION_DOMAIN = "keyguard-roster-revocation/v1"
    private const val GROUP_DOMAIN = "keyguard-group/v1"

    private const val FIELD_SIG = "sig"

    /** The size of a UUID, in bytes. */
    private const val UUID_SIZE = 16

    /** The fields no signature of a device record covers: the signatures themselves. */
    private val signatureFields = setOf(RosterDevice.FIELD_ADMISSION, RosterDevice.FIELD_SELF_SIG)

    fun selfBytes(groupId: String, device: RosterDevice): ByteArray =
        signedBytes(SELF_DOMAIN, context(groupId), JsonObject(device.raw - signatureFields))

    fun admissionBytes(groupId: String, device: RosterDevice): ByteArray =
        signedBytes(ADMISSION_DOMAIN, context(groupId), identity(device))

    fun revocationBytes(groupId: String, revocation: Revocation): ByteArray =
        signedBytes(REVOCATION_DOMAIN, context(groupId), JsonObject(revocation.raw - FIELD_SIG))

    /** `true` if [a] and [b] are records of one device with the same identity. */
    fun sameIdentity(a: RosterDevice, b: RosterDevice): Boolean = identity(a) == identity(b)

    /** The canonical JSON of the identity of [device], the fields its admission covers. */
    fun identityText(device: RosterDevice): String = CanonicalJson.encode(identity(device))

    /** The id of the group [founder] founds: the first 16 bytes of a SHA-256 of its identity, as a UUID. */
    fun groupIdOf(founder: RosterDevice, hasher: (ByteArray) -> ByteArray): String {
        val digest = hasher(signedBytes(GROUP_DOMAIN, identityText(founder)))
        return Uuid.fromByteArray(digest.copyOf(UUID_SIZE)).toString()
    }

    /**
     * The identity of a device: its record without the fields that change and without its
     * signatures. A field a newer build adds is part of it.
     */
    private fun identity(device: RosterDevice): JsonObject =
        JsonObject(device.raw - RosterDevice.MUTABLE_FIELDS - signatureFields)

    private fun context(groupId: String): Map<String, String> = mapOf("group" to groupId)
}

/**
 * The identity hash of this device, as a revocation seals it, see
 * [com.artemchep.keyguard.feature.p2psync.model.Revocation.admitted]: the base32 [hasher]
 * digest of the canonical JSON of its identity, the record without its mutable fields and
 * signatures, its id and keys included.
 */
fun RosterDevice.identityHash(hasher: (ByteArray) -> ByteArray): String =
    P2pEncoding.base32(hasher(RosterSignatures.identityText(this).encodeToByteArray()))

/**
 * The id of the sync group this device founds: the first 16 bytes of the [hasher] digest
 * of `keyguard-group/v1`, a line break and the canonical JSON of its identity, as a UUID.
 * A roster is valid only if its founder derives the id of its group.
 */
fun RosterDevice.derivedGroupId(hasher: (ByteArray) -> ByteArray): String = RosterSignatures.groupIdOf(this, hasher)

/** The strictly sorted identity hashes of the [kept] devices that [deviceId] admitted, but itself. */
internal fun Roster.admittedBy(
    deviceId: String,
    hasher: (ByteArray) -> ByteArray,
    kept: (RosterDevice) -> Boolean = { true },
): List<String> = devices
    .filter { it.addedBy == deviceId && it.id != deviceId && kept(it) }
    .map { it.identityHash(hasher) }
    .distinct()
    .sorted()
