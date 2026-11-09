package com.artemchep.keyguard.provider.keyguard.enroll

import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.feature.p2psync.enroll.DisplayMode
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentRole
import kotlin.time.Instant

/** A step of pairing two devices, as the pairing screens show it. */
sealed interface EnrollmentStep {
    /** Choose whether this device shows a code or scans one. */
    data object Method : EnrollmentStep

    /** Show [code] as a QR code and as text; it works once, until [expiresAt]. */
    data class ShowCode(
        val code: String,
        val expiresAt: Instant,
    ) : EnrollmentStep

    /** Waiting for the code of the other device, scanned or pasted. */
    data object Scanning : EnrollmentStep

    /** Reaching the other device and running the handshake. */
    data object Connecting : EnrollmentStep

    /**
     * Check the digits [sas] with the device [peerLabel]. The device that showed the code,
     * the [DisplayMode.DISPLAYER], has its user type the digits the other device shows, see
     * [KeyguardEnrollment.enterDigits]: anyone who saw the code could have reached it, and
     * typing proves its user looked at the real other device. The [DisplayMode.SCANNER]
     * pinned the key of the displayer from the code: it shows the digits, and its user
     * confirms that the displayer shows the same, see [KeyguardEnrollment.approve].
     *
     * The confirmation of the [EnrollmentRole.MEMBER] admits the other device; the one of
     * the [EnrollmentRole.JOINER] takes its data. The pairing goes on once both users
     * confirmed.
     *
     * @property display whether this device showed the code or scanned it.
     * @property confirmed `true` once this device's user confirmed the digits.
     * @property misses how many typed digits did not match.
     */
    data class Verify(
        val sas: String,
        val peerLabel: String,
        val role: EnrollmentRole,
        val display: DisplayMode,
        val confirmed: Boolean = false,
        val misses: Int = 0,
    ) : EnrollmentStep

    /** Moving the data of the sync group to the joining device. */
    data object Transferring : EnrollmentStep

    /** The devices are paired; [groupId] is the sync group on this device. */
    data class Done(
        val groupId: P2pGroupId,
    ) : EnrollmentStep

    /**
     * The pairing ended for [reason]; the code is burnt. A [KeyguardPairingError.FAILED]
     * keeps the [cause], so the screens can say what went wrong.
     */
    data class Failed(
        val reason: KeyguardPairingError,
        val cause: Throwable? = null,
    ) : EnrollmentStep
}

/** Pairing can not go on, for the [error] it shows. */
class KeyguardPairingException(
    val error: KeyguardPairingError,
    message: String,
) : IllegalStateException(message)

/** Why pairing two devices failed. */
enum class KeyguardPairingError {
    /** The code expired before the devices connected. */
    EXPIRED,

    /** The user of the other device said no. */
    REJECTED,

    /** The other device is not the one of the code, or the devices disagree on who is who. */
    MISMATCH,

    /** The other device speaks only a newer pairing protocol: this device needs an update. */
    UPDATE_REQUIRED,

    /** The other device speaks only an older pairing protocol: it needs an update. */
    PEER_UPDATE_REQUIRED,

    /** The devices could not reach each other, or the other one stopped answering. */
    NETWORK,

    /** This device's user stopped the pairing. */
    CANCELLED,

    /** The text is not a pairing code. */
    INVALID_CODE,

    /** The code pairs into another kind of sync group. */
    WRONG_KIND,

    /** Both apps are linked to other devices: one must leave its link first. */
    ALREADY_LINKED,

    /** The two apps are linked to each other already. */
    LINKED_TOGETHER,

    /** The synced data is too large to hand over to the new device. */
    TOO_LARGE,

    /** The data of the sync group could not be stored or read on this device. */
    FAILED,
}
