package com.artemchep.keyguard.feature.applink.connection

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.GetAppLinkThisDevice
import com.artemchep.keyguard.common.usecase.PutAppLinkNetwork
import com.artemchep.keyguard.feature.auth.keyguard.keyguardNetworkFlow
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.compose.currentKoinScope

@Composable
fun produceAppLinkConnectionScreenState(): Loadable<AppLinkConnectionState> = with(currentKoinScope()) {
    val getAppLinkThisDevice = get<GetAppLinkThisDevice>()
    val putAppLinkNetwork = get<PutAppLinkNetwork>()
    produceScreenState(
        initial = Loadable.Loading,
        key = "app_link_connection",
        args = arrayOf(
            getAppLinkThisDevice,
            putAppLinkNetwork,
        ),
    ) {
        appLinkConnectionStateProducer(
            getAppLinkThisDevice = getAppLinkThisDevice,
            putAppLinkNetwork = putAppLinkNetwork,
        )
    }
}

/**
 * The network settings of this device, filled in with the saved ones. They apply to the
 * link if there is one, and to the next one otherwise.
 */
suspend fun RememberStateFlowScope.appLinkConnectionStateProducer(
    getAppLinkThisDevice: GetAppLinkThisDevice,
    putAppLinkNetwork: PutAppLinkNetwork,
): Flow<Loadable<AppLinkConnectionState>> {
    val saveExecutor = screenExecutor()

    val storedNetworkFlow = getAppLinkThisDevice()
        .map { it.network }
        .distinctUntilChanged()
    val networkFlow = keyguardNetworkFlow(
        initial = storedNetworkFlow.first(),
    )
    return combine(
        networkFlow,
        storedNetworkFlow,
        saveExecutor.isExecutingFlow,
    ) { network, storedNetwork, isSaving ->
        val onSave = network.networkOrNull()
            ?.takeIf { it != storedNetwork && !isSaving }
            ?.let { newNetwork ->
                // lambda
                {
                    val io = putAppLinkNetwork(newNetwork)
                        .effectTap { navigatePopSelf() }
                    saveExecutor.execute(io)
                    Unit
                }
            }
        val state = AppLinkConnectionState(
            network = network.state,
            onSave = onSave,
            isSaving = isSaving,
        )
        Loadable.Ok(state)
    }
}
