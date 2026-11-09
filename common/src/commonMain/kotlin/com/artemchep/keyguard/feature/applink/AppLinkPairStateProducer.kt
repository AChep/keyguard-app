package com.artemchep.keyguard.feature.applink

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.ConfirmAccessByPasswordUseCase
import com.artemchep.keyguard.common.usecase.GetAppLinkThisDevice
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.feature.auth.keyguard.KeyguardPairState
import com.artemchep.keyguard.feature.auth.keyguard.KeyguardPairingIdentity
import com.artemchep.keyguard.feature.auth.keyguard.keyguardAuthorityPasswordPrompt
import com.artemchep.keyguard.feature.auth.keyguard.keyguardPairStateProducer
import com.artemchep.keyguard.feature.auth.keyguard.pairing.AppLinkPairingLauncher
import com.artemchep.keyguard.feature.auth.keyguard.pairing.PairingMode
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardEnrollmentCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.compose.currentKoinScope

@Composable
fun produceAppLinkPairScreenState(
    mode: PairingMode,
): Loadable<KeyguardPairState> = with(currentKoinScope()) {
    val coordinator = get<KeyguardEnrollmentCoordinator>()
    val getAppLinkThisDevice = get<GetAppLinkThisDevice>()
    val getPurchased = get<GetPurchased>()
    val confirmationRouteFactory = get<ConfirmationRouteFactory>()
    val confirmAccessByPassword = get<ConfirmAccessByPasswordUseCase>()
    produceScreenState(
        initial = Loadable.Loading,
        key = "app_link_pair:$mode",
        args = arrayOf(
            mode,
            coordinator,
            getAppLinkThisDevice,
            getPurchased,
            confirmationRouteFactory,
            confirmAccessByPassword,
        ),
    ) {
        appLinkPairStateProducer(
            mode = mode,
            coordinator = coordinator,
            getAppLinkThisDevice = getAppLinkThisDevice,
            getPurchased = getPurchased,
            confirmationRouteFactory = confirmationRouteFactory,
            confirmAccessByPassword = confirmAccessByPassword,
        )
    }
}

/**
 * Pairs this app with the app on another device, as this device of the linked devices
 * screen: a linked app admits the other one, see
 * [KeyguardEnrollmentCoordinator.pairApp]. This device shows or scans a code, as the
 * [mode] says. Linking needs a purchase or a self-hosted relay, and each pairing the master password.
 */
// The dependencies of the screen.
@Suppress("LongParameterList")
fun RememberStateFlowScope.appLinkPairStateProducer(
    mode: PairingMode,
    coordinator: KeyguardEnrollmentCoordinator,
    getAppLinkThisDevice: GetAppLinkThisDevice,
    getPurchased: GetPurchased,
    confirmationRouteFactory: ConfirmationRouteFactory,
    confirmAccessByPassword: ConfirmAccessByPasswordUseCase,
): Flow<Loadable<KeyguardPairState>> {
    // Both who this device pairs as and whether it may pair read this device.
    val thisDeviceFlow = getAppLinkThisDevice()
        .shareInScreenScope()
    return keyguardPairStateProducer(
        mode = mode,
        pairingLauncher = AppLinkPairingLauncher(coordinator),
        password = keyguardAuthorityPasswordPrompt(confirmationRouteFactory, confirmAccessByPassword),
        getIdentity = {
            thisDeviceFlow
                .map { device ->
                    KeyguardPairingIdentity(
                        label = device.label,
                        network = device.network,
                    )
                }
        },
        getCanPair = {
            appLinkCanLinkFlow(getPurchased, thisDeviceFlow)
        },
    )
}
