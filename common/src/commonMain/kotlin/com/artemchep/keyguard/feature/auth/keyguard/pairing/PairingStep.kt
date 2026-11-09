package com.artemchep.keyguard.feature.auth.keyguard.pairing

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentRole
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardPairingError
import kotlin.time.Instant

/** Which of the two devices shows the code. */
enum class PairingMode {
    /** This device shows the code; the other one scans it. */
    SHOW_CODE,

    /** This device scans, or pastes, the code of the other one. */
    SCAN_CODE,
}

/** A step of a pairing, as the pairing screens show it. */
@Immutable
sealed interface PairingStep {
    /** This device is getting ready. */
    data object Starting : PairingStep

    /**
     * Show the [code] as a QR code and as text, until [expiresAt]. A code works
     * once: after a failure the step keeps the [error] and offers [onNewCode].
     */
    data class ShowCode(
        val code: String,
        val expiresAt: Instant,
        /** Why the code can no longer be used, or `null` while it can. */
        val error: KeyguardPairingError? = null,
        /** What went wrong, from the cause of a [KeyguardPairingError.FAILED]. */
        val message: String? = null,
        /** Shows a new code; set once the code can no longer be used. */
        val onNewCode: (() -> Unit)? = null,
        val onCopy: () -> Unit,
    ) : PairingStep {
        val expired: Boolean get() = error != null
    }

    /**
     * Scan, load or paste the code of the other device. A code that is not a
     * pairing code shows an error in [code] and is not handed over.
     */
    data class Scan(
        val code: TextFieldModel,
        /** Hands over a code the camera or an image produced. */
        val onCode: (String) -> Unit,
        /** Hands over the text of [code]; `null` while it is not a pairing code. */
        val onSubmit: (() -> Unit)?,
    ) : PairingStep

    /** Reaching the other device. */
    data object Connecting : PairingStep

    /**
     * This device showed the code: type the digits the device [peerLabel] shows. Anyone who
     * saw the code could have reached this device; typing them proves that the user looks
     * at the real other device. A wrong entry shows an error in [digits]: the digits never
     * change, so the user may type them again, or [onCancel].
     */
    data class EnterSas(
        val peerLabel: String,
        val role: EnrollmentRole,
        /** The typed digits; six of them are checked at once. */
        val digits: TextFieldModel,
        /** Checks the typed digits; `null` until there are six of them. */
        val onSubmit: (() -> Unit)?,
        /** Ends the pairing. */
        val onCancel: () -> Unit,
    ) : PairingStep

    /**
     * The [sas] digits, checked with the device [peerLabel]. This device scanned the code:
     * its user enters the digits on the other device, then confirms with [onApprove] that
     * both show the same, or says with [onReject] that they differ.
     *
     * Once [confirmed], this device's user confirmed the digits, by typing them if
     * [entered]: the step shows them as matching and waits for the other device. As the
     * [EnrollmentRole.MEMBER], the confirmation admits the other device.
     */
    data class Verify(
        val sas: String,
        val peerLabel: String,
        val role: EnrollmentRole,
        /** Confirms the digits; `null` once they are. */
        val onApprove: (() -> Unit)?,
        /**
         * Ends the pairing: before the user confirmed, because the digits differ, with
         * [KeyguardPairingError.MISMATCH].
         */
        val onReject: () -> Unit,
        val confirmed: Boolean = false,
        /** `true` if this device showed the code, so its user typed the digits. */
        val entered: Boolean = false,
    ) : PairingStep

    /** Moving the group to the joining device. */
    data object Transferring : PairingStep

    /** The devices are paired with the device [peerLabel]. */
    data class Done(
        val peerLabel: String?,
    ) : PairingStep

    /** The pairing ended for [error]. */
    data class Failed(
        val error: KeyguardPairingError,
        /** What went wrong, from the cause of a [KeyguardPairingError.FAILED]. */
        val message: String? = null,
        val onRetry: (() -> Unit)?,
    ) : PairingStep
}

/** The longest device name the pairing screens show in full. */
const val PAIRING_PEER_LABEL_MAX_LENGTH = 40

/** Returns the [label] of the other device, cut to [PAIRING_PEER_LABEL_MAX_LENGTH] characters. */
fun truncatePairingPeerLabel(label: String): String =
    if (label.length > PAIRING_PEER_LABEL_MAX_LENGTH) {
        label.take(PAIRING_PEER_LABEL_MAX_LENGTH - 1).trimEnd() + "…"
    } else {
        label
    }

/** Returns the six [sas] digits as two groups of three: `123 456`. */
fun formatPairingSas(sas: String): String =
    if (sas.length == SAS_LENGTH) {
        sas.take(SAS_GROUP) + " " + sas.drop(SAS_GROUP)
    } else {
        sas
    }

internal const val SAS_LENGTH = 6

private const val SAS_GROUP = 3
