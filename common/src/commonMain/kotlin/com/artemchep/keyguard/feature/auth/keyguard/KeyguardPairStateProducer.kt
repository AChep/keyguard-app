package com.artemchep.keyguard.feature.auth.keyguard

import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.keyguard.pairing.KeyguardPairingLauncher
import com.artemchep.keyguard.feature.auth.keyguard.pairing.PairingController
import com.artemchep.keyguard.feature.auth.keyguard.pairing.PairingMode
import com.artemchep.keyguard.feature.auth.keyguard.pairing.PairingStep
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.provider.keyguard.KeyguardPasswordPrompt
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_pairing_as
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The pairing screen, which pairs this device with another one with a pairing of the
 * [pairingLauncher] in the [mode] the user picked; which of the two admits the other one
 * is not the user's choice. The pairing starts at once, as this device of [getIdentity];
 * a new code, a retry or one more device pairs as the device is by then. Each pairing
 * asks for the master password with [password] first.
 *
 * @param getIdentity emits who this device pairs as; it is set outside of this screen.
 * @param getCanPair emits whether this device may pair; pairing may need Premium.
 */
fun RememberStateFlowScope.keyguardPairStateProducer(
    mode: PairingMode,
    pairingLauncher: KeyguardPairingLauncher,
    password: KeyguardPasswordPrompt,
    getIdentity: () -> Flow<KeyguardPairingIdentity>,
    getCanPair: () -> Flow<Boolean>,
): Flow<Loadable<KeyguardPairState>> {
    val onSuccessFlow = EventFlow<Unit>()

    val pairings = PairingController(this)
    pairings.doneFlow
        .onEach { onSuccessFlow.emit(Unit) }
        .launchIn(screenScope)

    val identityFlow = getIdentity()
        .stateIn(screenScope, SharingStarted.Eagerly, null)
    val canPairFlow = getCanPair()
        .stateIn(screenScope, SharingStarted.Eagerly, null)

    fun start() = pairings.start(mode) { scope ->
        val identity = checkNotNull(identityFlow.value)
        pairingLauncher.pair(
            deviceLabel = identity.label,
            network = identity.network,
            password = password,
            scope = scope,
        )
    }

    // Pairing may need Premium: start once this device may.
    screenScope.launch {
        combine(identityFlow, canPairFlow) { identity, canPair ->
            identity != null && canPair == true
        }.first { it }
        start()
    }

    return combine(
        identityFlow.filterNotNull(),
        canPairFlow.filterNotNull(),
        pairings.stateFlow,
    ) { identity, canPair, pairing ->
        val pairingAsText = translate(
            Res.string.app_link_pairing_as,
            identity.label,
            translateKeyguardRelayName(identity.network),
        )
        val step = pairing?.step
        val onAddAnother = if (step is PairingStep.Done) {
            // lambda
            {
                start()
            }
        } else {
            null
        }
        val state = KeyguardPairState(
            pairingAsText = pairingAsText,
            premiumRequired = !canPair,
            step = step,
            onAddAnother = onAddAnother,
            sideEffects = KeyguardPairState.SideEffects(
                onSuccessFlow = onSuccessFlow,
            ),
        )
        Loadable.Ok(state)
    }
}
