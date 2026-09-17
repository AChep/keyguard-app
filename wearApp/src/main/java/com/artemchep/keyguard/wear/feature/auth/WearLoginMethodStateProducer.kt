package com.artemchep.keyguard.wear.feature.auth

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthBridgeAndroid
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.currentKoinScope

@Composable
internal fun wearLoginMethodScreenState(
    accountType: AccountType,
    transmitter: RouteResultTransmitter<Unit>,
): WearLoginMethodState = with(currentKoinScope()) {
    wearLoginMethodScreenState(
        accountType = accountType,
        transmitter = transmitter,
        companionAuthBridge = get(),
    )
}

@Composable
internal fun wearLoginMethodScreenState(
    accountType: AccountType,
    transmitter: RouteResultTransmitter<Unit>,
    companionAuthBridge: CompanionAuthBridgeAndroid,
): WearLoginMethodState = produceScreenState(
    key = "wear_login_method",
    initial = WearLoginMethodState(),
    args = arrayOf(
        accountType,
        companionAuthBridge,
    ),
) {
    val phoneAvailabilityFlow = companionAuthBridge
        .phoneAvailabilityFlow()
        .distinctUntilChanged()

    val onPhoneLoginClick = onClick {
        val route = registerRouteResultReceiver(
            route = WearPhoneLoginRoute(accountType = accountType),
        ) {
            navigate(NavigationIntent.Pop)
            transmitter(Unit)
        }
        navigate(NavigationIntent.NavigateToRoute(route))
    }
    val onManualLoginClick = onClick {
        val route = registerRouteResultReceiver(
            route = manualLoginRouteOf(accountType),
        ) {
            navigate(NavigationIntent.Pop)
            transmitter(Unit)
        }
        navigate(NavigationIntent.NavigateToRoute(route))
    }

    phoneAvailabilityFlow.map { isPhoneAvailable ->
        val isPhoneLoginEnabled = shouldEnablePhoneLogin(
            isPhoneAvailable = isPhoneAvailable,
        )
        val isManualLoginVisible = shouldShowManualLogin(
            accountType = accountType,
        )
        WearLoginMethodState(
            infoText = infoTextOf(
                accountType = accountType,
            ),
            phoneStatusText = phoneStatusTextOf(
                isPhoneAvailable = isPhoneAvailable,
                requestState = null,
            ),
            isPhoneLoginEnabled = isPhoneLoginEnabled,
            onPhoneLoginClick = if (isPhoneLoginEnabled) {
                onPhoneLoginClick
            } else {
                null
            },
            isManualLoginVisible = isManualLoginVisible,
            onManualLoginClick = if (isManualLoginVisible) {
                onManualLoginClick
            } else {
                null
            },
        )
    }
}
