package com.artemchep.keyguard.feature.auth.keyguard

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.auth.keyguard.pairing.PairingStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Immutable
data class KeyguardPairState(
    /**
     * Who this device pairs as, for example "Pairing as Pixel 9 via Keyguard relay".
     * The name and the relay belong to this device, not to one pairing.
     */
    val pairingAsText: String,
    /** `true` if linking a device needs Premium; the pairing then does not start. */
    val premiumRequired: Boolean = false,
    /** The pairing in progress, or `null` until it starts. */
    val step: PairingStep? = null,
    /** Pairs with one more device, in the same way; set once the devices are paired. */
    val onAddAnother: (() -> Unit)? = null,
    val sideEffects: SideEffects = SideEffects(),
) {
    @Immutable
    data class SideEffects(
        val onSuccessFlow: Flow<Unit> = emptyFlow(),
    )
}
