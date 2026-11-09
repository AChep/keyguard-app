package com.artemchep.keyguard.feature.auth.keyguard.pairing

import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.provider.keyguard.KeyguardPasswordPrompt
import com.artemchep.keyguard.provider.keyguard.enroll.EnrollmentStep
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardEnrollment
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardEnrollmentCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * One pairing of two devices, as the pairing screens drive it. The app
 * runs a [KeyguardEnrollment]; tests run a fake.
 */
interface KeyguardPairing {
    val step: StateFlow<EnrollmentStep>

    /** This device shows the code. */
    fun showCode()

    /** This device scans, or pastes, the code of the other one. */
    fun scanCode()

    /** Hands over the text of the other device's code. */
    fun submitCode(text: String)

    /** The scanner's user saw the same digits on both devices. */
    fun approve()

    /** The displayer's user typed the [digits] the other device shows. */
    fun enterDigits(digits: String)

    /** The digits differ, or the user does not want the device. */
    fun reject()

    /** Stops the pairing, telling the other device. */
    fun cancel()
}

/**
 * Starts pairings. Each pairing asks for the master password with its [KeyguardPasswordPrompt]
 * once it starts: a device admits another one, or gets the key to do so, only with it.
 */
interface KeyguardPairingLauncher {
    /**
     * Starts pairing with another device as the device named [deviceLabel], with the
     * [network] settings; it runs in [scope]. Which of the two devices admits the other
     * one follows from their sync groups, not from the user.
     */
    fun pair(
        deviceLabel: String,
        network: P2pNetwork,
        password: KeyguardPasswordPrompt,
        scope: CoroutineScope,
    ): KeyguardPairing
}

/**
 * Starts the pairings of linked apps with the [coordinator], see
 * [KeyguardEnrollmentCoordinator.pairApp]. A linked app keeps its name and settings.
 */
class AppLinkPairingLauncher(
    private val coordinator: KeyguardEnrollmentCoordinator,
) : KeyguardPairingLauncher {
    override fun pair(
        deviceLabel: String,
        network: P2pNetwork,
        password: KeyguardPasswordPrompt,
        scope: CoroutineScope,
    ): KeyguardPairing = coordinator.pairApp(
        deviceLabel = deviceLabel,
        network = network,
        password = password,
        scope = scope,
    )
}
