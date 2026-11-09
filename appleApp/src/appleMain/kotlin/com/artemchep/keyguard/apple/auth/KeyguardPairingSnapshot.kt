package com.artemchep.keyguard.apple.auth

/**
 * A pairing of two devices of linked apps, in the way the user picked, as this device of
 * the linked devices screen, see [pairingAsText]; [step] stays `null` until it starts.
 */
data class KeyguardPairingSnapshot(
    /** `false` until the producer emits. */
    val loaded: Boolean,
    val step: KeyguardPairingStepSnapshot?,
    /** What the stage above the step shows, the same as on Compose. */
    val stage: Stage,
    /** For example "Pairing as Pixel 9 via Keyguard relay". */
    val pairingAsText: String,
    /** `true` if linking needs Premium; the pairing then does not start. */
    val premiumRequired: Boolean,
    /** Pairs one more device, once the devices are linked. */
    val canAddAnother: Boolean,
) {
    /** What the stage of a pairing shows, see `PairingStagePhase`. */
    enum class Stage {
        IDLE,
        STARTING,
        SHOW_CODE,
        SCAN,
        CONNECTING,
        VERIFY,
        TRANSFERRING,
        DONE,
        FAILED,
    }

    companion object {
        val empty = KeyguardPairingSnapshot(
            loaded = false,
            step = null,
            stage = Stage.IDLE,
            pairingAsText = "",
            premiumRequired = false,
            canAddAnother = false,
        )
    }
}
