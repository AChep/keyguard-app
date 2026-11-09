package com.artemchep.keyguard.feature.p2psync.enroll

import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.decodeOrNull
import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import com.artemchep.keyguard.feature.p2psync.model.EnvelopeCodec
import com.artemchep.keyguard.feature.p2psync.model.JsonLimits
import com.artemchep.keyguard.feature.p2psync.model.PairingCode
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import com.artemchep.keyguard.feature.p2psync.session.Frame
import com.artemchep.keyguard.feature.p2psync.session.FrameCodec
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionConfig
import com.artemchep.keyguard.feature.p2psync.session.encodePayload
import com.artemchep.keyguard.feature.p2psync.transport.NOISE_HANDSHAKE_TIMEOUT_MS
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Everything a joining device needs to become a member: the group and its key, the roster
 * that admits it, the synced entries and where to find the relay. The joiner checks that
 * the [group] id derives from the founder of the [roster].
 *
 * @property group the id of the sync group.
 * @property groupKey the base64url 32-byte group key.
 * @property entries the synced entries of the member, as its envelope holds them.
 */
@Serializable
data class EnrollmentBundle(
    val group: String,
    val groupKey: String,
    val roster: Roster,
    val entries: List<SyncEntry>,
    val relay: String? = null,
    val ice: String? = null,
) {
    /**
     * `true` if the [roster] admits [device] as an active device, with the keys of [device]:
     * a record of the same id with other keys would hand the group to another device.
     * [hasher] is SHA-256, see [RosterActivity].
     */
    fun admits(device: RosterDevice, hasher: (ByteArray) -> ByteArray): Boolean {
        val sameKeys = roster.devices.any {
            it.id == device.id &&
                it.noisePub == device.noisePub &&
                it.signPub == device.signPub &&
                it.authorityPub == device.authorityPub
        }
        return sameKeys && RosterActivity(roster, hasher).isActive(device.id)
    }
}

/**
 * Reads and writes enrollment bundles. The entries of a bundle are read as leniently as the
 * ones of an envelope, see [EnvelopeCodec]: the entries this build can not read are skipped,
 * so an older device can still join. A bundle with a number that has no canonical form, see
 * [CanonicalJson], is malformed.
 */
internal object EnrollmentBundleCodec {
    private const val FIELD_ENTRIES = "entries"

    /**
     * The limits of a bundle: as deep as an envelope, with as many nodes as the largest
     * envelope and the roster, see [EnrollmentConfig.MAX_BUNDLE_SIZE].
     */
    val limits: JsonLimits = JsonLimits(maxDepth = EnvelopeCodec.MAX_DEPTH, maxNodes = 2 * EnvelopeCodec.MAX_NODES)

    fun encode(bundle: EnrollmentBundle): ByteArray = EnrollmentBundle.serializer().encodePayload(bundle)

    /** Decodes a bundle, or returns `null` if the bytes are not one. */
    fun decodeOrNull(bytes: ByteArray): EnrollmentBundle? {
        val json = EnvelopeCodec.parseObjectOrNull(bytes, limits)?.takeIf(CanonicalJson::isCanonical)
        val entries = json?.get(FIELD_ENTRIES) as? JsonArray
        if (json == null || entries == null) return null
        // The entries are decoded apart, leniently.
        val rest = JsonObject(json + (FIELD_ENTRIES to JsonArray(emptyList())))
        val bundle = EnrollmentBundle.serializer().decodeOrNull(rest)
        return bundle?.copy(entries = EnvelopeCodec.decodeEntries(entries))
    }
}

/** Whether a device already is a member of the sync group. */
@Serializable
enum class EnrollmentRole {
    /** A member of the sync group; admits the other device. */
    MEMBER,

    /** Joins the sync group. */
    JOINER,
}

/**
 * The role of a device in a pairing, so that its user does not pick one: a member keeps its
 * sync group and admits the other device. The displayer can not know the other device, so it
 * joins unless it is a member; the scanner learns from the [scanned] code whether the
 * displayer is a member, and admits it otherwise, founding a group if it has none.
 *
 * @param isMember `true` if this device is a member of a sync group with other devices.
 * @param scanned the code of the displayer, or `null` if this device shows the code.
 * @return the role, or `null` if both devices are members: one must leave its group first.
 */
fun chooseEnrollmentRole(
    isMember: Boolean,
    scanned: PairingCode?,
): EnrollmentRole? = when {
    scanned == null -> if (isMember) EnrollmentRole.MEMBER else EnrollmentRole.JOINER
    !scanned.member -> EnrollmentRole.MEMBER
    isMember -> null
    else -> EnrollmentRole.JOINER
}

/** Whether a device shows the pairing code or scans it. Independent of [EnrollmentRole]. */
enum class DisplayMode {
    /** Shows the code; desktops always do. */
    DISPLAYER,

    /** Scans, or pastes, the code. */
    SCANNER,
}

/**
 * The versions of the enrollment protocol. ENROLL_HELLO carries both of each device: the
 * devices speak the lower of their [CURRENT] versions, if both support it, see
 * [EnrollmentStateMachine.version].
 */
object EnrollmentVersion {
    /** The version this build speaks. */
    const val CURRENT: Int = 1

    /** The oldest version this build still speaks. */
    const val MIN_SUPPORTED: Int = 1
}

/** The bounded waits of an enrollment. */
data class EnrollmentTimings(
    /** How long a shown code is valid. */
    val inviteTtlMs: Long = INVITE_TTL_MS,
    /** How long the Noise handshake and the HELLO exchange may take. */
    val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    /** How long the member's user has to compare the digits and approve. */
    val approvalTimeoutMs: Long = APPROVAL_TIMEOUT_MS,
    /** How long the bundle transfer may take. */
    val bundleTimeoutMs: Long = BUNDLE_TIMEOUT_MS,
    /** How long the member waits for the joiner's acknowledgement. */
    val ackTimeoutMs: Long = ACK_TIMEOUT_MS,
    /**
     * How far apart the clocks of the devices may be. The displayer sets the expiry of its
     * code by its own clock, so the scanner turns down only a code expired by more than this.
     */
    val clockSkewMs: Long = CLOCK_SKEW_MS,
) {
    companion object {
        /** 3 minutes. */
        const val INVITE_TTL_MS: Long = 3L * 60L * 1_000L

        /** 20 seconds. */
        const val HANDSHAKE_TIMEOUT_MS: Long = NOISE_HANDSHAKE_TIMEOUT_MS

        /** 2 minutes. */
        const val APPROVAL_TIMEOUT_MS: Long = 120_000L

        /** 1 minute. */
        const val BUNDLE_TIMEOUT_MS: Long = 60_000L

        /** 15 seconds. */
        const val ACK_TIMEOUT_MS: Long = 15_000L

        /** 5 minutes, as much as the clocks of a sync group may drift apart. */
        const val CLOCK_SKEW_MS: Long = HlcClock.MAX_FUTURE_DRIFT_MS
    }
}

/**
 * The setup of one enrollment.
 *
 * @property device the record of this device: the member's record, or the joiner's own
 *   record waiting for admission. The joiner signs the record it announces once it learns
 *   the group, see [EnrollmentEvent.HandshakeDone.announce].
 * @property group the id of the sync group of a member, which its ENROLL_HELLO names: the
 *   joiner's record is signed for it.
 * @property scope what the sync group syncs; both devices must pair into the same scope.
 * @property version the enrollment protocol version this device speaks, see [EnrollmentVersion].
 * @property minVersion the oldest enrollment protocol version this device still speaks.
 */
data class EnrollmentConfig(
    val role: EnrollmentRole,
    val display: DisplayMode,
    val device: RosterDevice,
    val group: String? = null,
    val relay: String? = null,
    val ice: String? = null,
    val timings: EnrollmentTimings = EnrollmentTimings(),
    val scope: SyncScope,
    /** The largest bundle, as JSON bytes, that a member sends. */
    val maxBundleSize: Int = MAX_BUNDLE_SIZE,
    val version: Int = EnrollmentVersion.CURRENT,
    val minVersion: Int = EnrollmentVersion.MIN_SUPPORTED,
) {
    companion object {
        /** 4 MiB: the largest envelope, and the roster. */
        const val MAX_BUNDLE_SIZE: Int = 2 * SyncSessionConfig.MAX_ENVELOPE_SIZE
    }
}

/** Why an enrollment failed. */
enum class EnrollmentFailure {
    /** The code expired before the devices connected. */
    EXPIRED,

    /** This device's user said no. */
    REJECTED,

    /** The other device ended the enrollment. */
    REJECTED_BY_PEER,

    /** The other device's key is not the one in the code or in its record. */
    MISMATCH,

    /** Both devices claim the same role, e.g. two members. */
    ROLE_MISMATCH,

    /** The other device speaks only newer enrollment protocols: this device needs an update. */
    UPDATE_REQUIRED,

    /** The other device speaks only older enrollment protocols: it needs an update. */
    PEER_UPDATE_REQUIRED,

    /** A step took longer than allowed. */
    TIMEOUT,

    /** The other device sent something this build can not read. */
    PROTOCOL_ERROR,

    /** The devices pair into different kinds of sync groups, see [SyncScope]. */
    SCOPE_MISMATCH,

    /** The synced state is larger than [EnrollmentConfig.maxBundleSize], too large to hand over. */
    TOO_LARGE,
}

/** An input of an [EnrollmentStateMachine]. */
sealed interface EnrollmentEvent {
    /** The scanner read [code] at [now]. */
    data class CodeScanned(val code: PairingCode, val now: Long) : EnrollmentEvent

    /**
     * The Noise XXpsk3 handshake finished.
     *
     * @property announce returns the joiner's record to announce in the group it names, if
     *   it differs from the one of the [EnrollmentConfig]: the record names its inviter,
     *   which the joiner learns once the devices meet, and its signatures cover the group,
     *   which the member's ENROLL_HELLO names. It must keep the configured id and Noise key.
     */
    class HandshakeDone(
        val remoteStatic: ByteArray,
        val handshakeHash: ByteArray,
        val announce: ((group: String) -> RosterDevice)? = null,
    ) : EnrollmentEvent

    /**
     * The member's user checked the digits and approved; [bundle] admits the joiner. Its
     * roster is not stored until the joiner acknowledges it, see
     * [EnrollmentEffect.SaveAdmission].
     */
    class Approved(val bundle: EnrollmentBundle) : EnrollmentEvent

    /** The joiner's user checked the digits and confirmed them. */
    data object Confirmed : EnrollmentEvent

    /** This device's user cancelled or said the digits differ. */
    data object Rejected : EnrollmentEvent

    /** A frame arrived over the Noise channel. */
    class Frame(val bytes: ByteArray) : EnrollmentEvent

    /** The clock reached [now]. */
    data class Tick(val now: Long) : EnrollmentEvent
}

/** What an [EnrollmentStateMachine] asks its driver to do. */
sealed interface EnrollmentEffect {
    /** Show [code] as a QR code and as text. */
    data class ShowCode(val code: PairingCode) : EnrollmentEffect

    /** Connect to the displayer of [code] and run the handshake as the initiator, pinning its key. */
    data class Connect(val code: PairingCode) : EnrollmentEffect

    /**
     * The devices agree on [digits]; [peer] is the record of the other device. The user of
     * each device checks them and confirms: the displayer types the digits the scanner
     * shows, the scanner compares them with the ones the displayer shows once they match.
     */
    data class ShowSas(val digits: String, val peer: RosterDevice) : EnrollmentEffect

    /** Write [frame] to the Noise channel. */
    class SendFrame(val frame: Frame) : EnrollmentEffect {
        /** The encoded frame. */
        val bytes: ByteArray get() = FrameCodec.encode(frame)

        override fun toString(): String = "SendFrame($frame)"
    }

    /**
     * The joiner stored the approved [bundle] and acknowledged it: the member stores its
     * roster, which admits the joiner. [Done] follows; a failure to store it must stop it.
     *
     * Such a failure, or an acknowledgement that never arrives, ends the enrollment of the
     * member as failed while the joiner, which stored the bundle, counts itself a member.
     * No device of the group knows it, so it syncs with no one until it leaves the link
     * and pairs again.
     */
    class SaveAdmission(val bundle: EnrollmentBundle) : EnrollmentEffect

    /** Store the received [bundle]: this device becomes a member. */
    class PersistBundle(val bundle: EnrollmentBundle) : EnrollmentEffect

    /** The enrollment succeeded. */
    data object Done : EnrollmentEffect

    /** The enrollment failed; the code is burnt. */
    data class Failed(val reason: EnrollmentFailure) : EnrollmentEffect
}

/**
 * The versions of ENROLL_HELLO, read before the rest of it: a device that speaks no common
 * version learns which one of the two needs an update, whatever else the HELLO holds.
 *
 * @property v the enrollment protocol version the sender speaks, see [EnrollmentVersion].
 * @property min the oldest version the sender still speaks.
 */
@Serializable
internal data class EnrollVersionsPayload(
    val v: Int,
    val min: Int,
)

/**
 * @property v the enrollment protocol version the sender speaks, see [EnrollVersionsPayload].
 * @property min the oldest version the sender still speaks.
 * @property scope the scope of the sync group the sender pairs into.
 * @property group the id of the sync group, sent by the member.
 * @property commit the displayer's commitment to its nonce, see [SasDerivation.commit].
 * @property nonce the scanner's nonce, sent once it holds the commitment.
 */
@Serializable
internal data class EnrollHelloPayload(
    val v: Int,
    val min: Int,
    val role: EnrollmentRole,
    val device: RosterDevice,
    val scope: SyncScope,
    val group: String? = null,
    val commit: String? = null,
    val nonce: String? = null,
)

/**
 * The displayer's nonce, which its ENROLL_HELLO committed to.
 *
 * @property device the record a joiner announces, signed for the group of the member's
 *   ENROLL_HELLO, which a displaying joiner did not know when it sent its own.
 */
@Serializable
internal data class EnrollRevealPayload(
    val nonce: String,
    val device: RosterDevice? = null,
)

@Serializable
internal data class EnrollRejectPayload(
    val reason: String,
)
