package com.artemchep.keyguard.wear.feature.auth

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthBridgeAndroid
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthPhoneDevice
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
internal fun wearPhoneLoginScreenState(
    accountType: AccountType,
    transmitter: RouteResultTransmitter<Unit>,
): WearPhoneLoginState = with(localDI().direct) {
    wearPhoneLoginScreenState(
        accountType = accountType,
        transmitter = transmitter,
        companionAuthBridge = instance(),
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
        if (phoneDevices.isEmpty()) {
            return
        }
        if (phoneDevices.size == 1) {
            startPhoneLogin(nodeId = phoneDevices.first().id)
            return
        }

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

    val phoneDevicesFlow = companionAuthBridge
        .reachablePhoneDevicesFlow()
        .distinctUntilChanged()

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

    phoneDevicesFlow
        .onEach { phoneDevices ->
            if (phoneDevices.isEmpty()) {
                return@onEach
            }
            if (didAutoLaunchSink.value) {
                return@onEach
            }
            if (activeRequestIdSink.value != null) {
                return@onEach
            }

            didAutoLaunchSink.value = true
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
        .launchIn(screenScope)

    val onActionClick = onClick {
        val phoneDevices = companionAuthBridge.getReachablePhoneDevices()
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

    combine(
        phoneDevicesFlow,
        requestStateFlow,
    ) { phoneDevices, requestState ->
        val actionEnabled = shouldEnablePhoneLoginAction(
            phoneDeviceCount = phoneDevices.size,
            requestState = requestState,
        )
        WearPhoneLoginState(
            statusText = phoneStatusTextOf(
                isPhoneAvailable = phoneDevices.isNotEmpty(),
                requestState = requestState,
            ),
            showProgress = shouldShowPhoneLoginProgress(requestState),
            actionText = if (actionEnabled) {
                phoneLoginActionTextOf(phoneDevices.size)
            } else {
                null
            },
            onActionClick = if (actionEnabled) {
                onActionClick
            } else {
                null
            },
        )
    }
}
