package com.artemchep.keyguard.feature.auth.keyguard.pairing

import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardPairingError

/** What the stage of a pairing shows, see [PairingStage]. */
enum class PairingStagePhase {
    /** Nothing happens yet: this device waits for another one. */
    IDLE,
    STARTING,

    /** This device shows a code: it calls out to the other one. */
    SHOW_CODE,

    /** This device scans the code of the other one. */
    SCAN,

    /** The devices shake hands. */
    CONNECTING,

    /** The devices met: compare the digits. */
    VERIFY,

    /** The data travels to the device that joins. */
    TRANSFERRING,

    /** The devices are one link now. */
    DONE,
    FAILED,
}

/** Returns what the stage shows for this step; `null` until the pairing starts. */
fun PairingStep?.toStagePhase(): PairingStagePhase = when (this) {
    null -> PairingStagePhase.IDLE
    PairingStep.Starting -> PairingStagePhase.STARTING
    is PairingStep.ShowCode -> when (error) {
        null -> PairingStagePhase.SHOW_CODE
        // An expired code is not a failure: there is a new one a tap away.
        KeyguardPairingError.EXPIRED -> PairingStagePhase.IDLE
        else -> PairingStagePhase.FAILED
    }

    is PairingStep.Scan -> PairingStagePhase.SCAN
    PairingStep.Connecting -> PairingStagePhase.CONNECTING
    is PairingStep.EnterSas,
    is PairingStep.Verify,
    -> PairingStagePhase.VERIFY

    PairingStep.Transferring -> PairingStagePhase.TRANSFERRING
    is PairingStep.Done -> PairingStagePhase.DONE
    is PairingStep.Failed -> PairingStagePhase.FAILED
}

/**
 * Where each part of the stage is headed in a phase, from 0 to 1.
 *
 * @property separation how far apart the devices are: 1 is apart, 0 together.
 * @property peer 0 while the other device is a ghost, 1 once it is there.
 * @property solid 0 while the link is dashed, 1 once it is solid.
 * @property wave how much the link waves.
 */
internal class PairingStageTargets(
    val separation: Float = 1f,
    val peer: Float = 0f,
    val solid: Float = 0f,
    val wave: Float = 0f,
    val broadcast: Float = 0f,
    val scan: Float = 0f,
    val handshake: Float = 0f,
    val transfer: Float = 0f,
    val breathe: Float = 0f,
    val failure: Float = 0f,
    val merge: Float = 0f,
)

internal fun PairingStagePhase.targets(): PairingStageTargets = when (this) {
    PairingStagePhase.IDLE,
    PairingStagePhase.STARTING,
    -> PairingStageTargets(breathe = 1f)

    PairingStagePhase.SHOW_CODE -> PairingStageTargets(breathe = 1f, broadcast = 1f)
    PairingStagePhase.SCAN -> PairingStageTargets(breathe = 1f, scan = 1f)
    PairingStagePhase.CONNECTING -> PairingStageTargets(peer = 0.6f, wave = 1f, handshake = 1f)
    PairingStagePhase.VERIFY -> PairingStageTargets(peer = 1f, solid = 1f)
    PairingStagePhase.TRANSFERRING -> PairingStageTargets(peer = 1f, solid = 1f, wave = 0.3f, transfer = 1f)
    PairingStagePhase.DONE -> PairingStageTargets(separation = 0f, peer = 1f, solid = 1f, merge = 1f)
    PairingStagePhase.FAILED -> PairingStageTargets(separation = 1.15f, peer = 1f, failure = 1f)
}
