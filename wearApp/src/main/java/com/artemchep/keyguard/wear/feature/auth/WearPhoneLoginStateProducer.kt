package com.artemchep.keyguard.wear.feature.auth

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthBridgeAndroid
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthPhoneDevice
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthPhoneDiscovery
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthRequestState
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.wear.feature.picker.WearPickerRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.compose.currentKoinScope

@Composable
internal fun wearPhoneLoginScreenState(
    accountType: AccountType,
    transmitter: RouteResultTransmitter<Unit>,
): WearPhoneLoginState = with(currentKoinScope()) {
    wearPhoneLoginScreenState(
        accountType = accountType,
        transmitter = transmitter,
        companionAuthBridge = get(),
    )
}

@Composable
@OptIn(ExperimentalCoroutinesApi::class)
internal fun wearPhoneLoginScreenState(
    accountType: AccountType,
    transmitter: RouteResultTransmitter<Unit>,
    companionAuthBridge: CompanionAuthBridgeAndroid,
): WearPhoneLoginState = produceScreenState(
    key = "wear_phone_login",
    initial = WearPhoneLoginState(),
    args = arrayOf(
        accountType,
        companionAuthBridge,
    ),
) {
    val activeRequestIdSink = mutablePersistedFlow<String?>("active_request_id") {
        null
    }
    val didAutoLaunchSink = mutablePersistedFlow("did_auto_launch") {
        false
    }

    suspend fun startPhoneLogin(
        nodeId: String? = null,
    ) {
        activeRequestIdSink.value = companionAuthBridge.startOnPhone(
            provider = companionAuthProviderOf(accountType),
            nodeId = nodeId,
        )
    }

    suspend fun showPhonePicker(
        phoneDevices: List<CompanionAuthPhoneDevice>,
    ) {
        val actions = phoneDevices.map { phoneDevice ->
            FlatItemAction(
                id = phoneDevice.id,
                title = TextHolder.Value(phoneDevice.displayName ?: phoneDevice.id),
                onClick = onClick {
                    startPhoneLogin(nodeId = phoneDevice.id)
                },
            )
        }
        navigate(
            NavigationIntent.NavigateToRoute(
                WearPickerRoute(actions = actions),
            ),
        )
    }

    suspend fun launchPhoneLogin(
        phoneDevices: List<CompanionAuthPhoneDevice>,
    ) {
        when (phoneLoginLaunchBehaviorOf(phoneDevices.size)) {
            WearPhoneLoginLaunchBehavior.NONE -> Unit
            WearPhoneLoginLaunchBehavior.AUTO_START -> {
                startPhoneLogin(nodeId = phoneDevices.first().id)
            }
            WearPhoneLoginLaunchBehavior.PICK_DEVICE -> {
                showPhonePicker(phoneDevices)
            }
        }
    }

    val phoneDiscoveryFlow = companionAuthBridge
        .phoneDiscoveryFlow()
        .persistingStateIn(screenScope, SharingStarted.WhileSubscribed(), CompanionAuthPhoneDiscovery.Loading)

    val requestStateFlow = activeRequestIdSink
        .flatMapLatest { requestId ->
            if (requestId == null) {
                flowOf<CompanionAuthRequestState?>(null)
            } else {
                companionAuthBridge.getRequestStateFlow(requestId)
            }
        }
        .distinctUntilChanged()
        .onEach { requestState ->
            if (requestState is CompanionAuthRequestState.Success) {
                activeRequestIdSink.value = null
                transmitter(Unit)
            }
        }

    phoneDiscoveryFlow
        .onEach { phoneDiscovery ->
            val shouldAutoLaunch = shouldAutoLaunchPhoneLogin(
                phoneDeviceCount = phoneDiscovery.devices.size,
                didAutoLaunch = didAutoLaunchSink.value,
                hasActiveRequest = activeRequestIdSink.value != null,
            )
            if (!shouldAutoLaunch) {
                return@onEach
            }

            didAutoLaunchSink.value = true
            launchPhoneLogin(phoneDiscovery.devices)
        }
        .launchIn(screenScope)

    val onActionClick = onClick {
        launchPhoneLogin(phoneDiscoveryFlow.value.devices)
    }

    combine(
        phoneDiscoveryFlow,
        requestStateFlow,
    ) { phoneDiscovery, requestState ->
        wearPhoneLoginStateOf(
            phoneDiscovery = phoneDiscovery,
            requestState = requestState,
            onActionClick = onActionClick,
        )
    }
}
