package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.apple.model.TextFieldSnapshot

/**
 * A step of a Keyguard pairing, as the pairing screens show it. Texts are
 * localized, and device names are cut to fit.
 */
sealed class KeyguardPairingStepSnapshot {
    /** This device is getting ready. */
    data object Starting : KeyguardPairingStepSnapshot()

    /**
     * Show the [code] as a QR code and as text until [expiresAtMs]. A code works
     * once: once [expired], [errorText] says why and a new code can be requested.
     */
    data class ShowCode(
        val code: String,
        val expiresAtMs: Long,
        val expired: Boolean,
        val errorText: String?,
    ) : KeyguardPairingStepSnapshot()

    /** Scan, load or paste the code of the other device; [canSubmit] once [code] holds one. */
    data class Scan(
        val code: TextFieldSnapshot,
        val canSubmit: Boolean,
    ) : KeyguardPairingStepSnapshot()

    /** Reaching the other device. */
    data object Connecting : KeyguardPairingStepSnapshot()

    /**
     * This device showed the code: type the digits the device [peerLabel] shows into
     * [digits], which holds the error of a wrong entry. Six digits are checked at once;
     * [canSubmit] once there are six. [isJoiner] is `true` on the device that joins.
     */
    data class EnterSas(
        val peerLabel: String,
        val digits: TextFieldSnapshot,
        val canSubmit: Boolean,
        val isJoiner: Boolean,
    ) : KeyguardPairingStepSnapshot()

    /**
     * The digits, grouped as `123 456`, checked with the device [peerLabel]. Until
     * [confirmed], this device scanned the code: its user enters the digits on the other
     * device, then confirms that both show the same ([canApprove]) or rejects. Once
     * [confirmed], the digits match, typed by this device's user if [entered], and the
     * device waits for the other one. [isJoiner] is `true` on the device that joins,
     * `false` on the member, which admits it.
     */
    data class Verify(
        val sasFormatted: String,
        val peerLabel: String,
        val canApprove: Boolean,
        val isJoiner: Boolean,
        val confirmed: Boolean,
        val entered: Boolean,
    ) : KeyguardPairingStepSnapshot()

    /** Moving the data to the joining device. */
    data object Transferring : KeyguardPairingStepSnapshot()

    /** The devices are paired with the device [peerLabel], if known. */
    data class Done(
        val peerLabel: String?,
    ) : KeyguardPairingStepSnapshot()

    /** The pairing ended; [errorText] says why. */
    data class Failed(
        val errorText: String,
        val canRetry: Boolean,
    ) : KeyguardPairingStepSnapshot()
}
