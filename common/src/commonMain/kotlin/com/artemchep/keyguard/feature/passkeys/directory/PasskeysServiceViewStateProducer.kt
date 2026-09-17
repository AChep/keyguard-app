package com.artemchep.keyguard.feature.passkeys.directory

import androidx.compose.runtime.Composable
import arrow.core.right
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.passkey.PassKeyService
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.currentKoinScope

@Composable
fun producePasskeysServiceViewState(
    args: PasskeysServiceViewDialogRoute.Args,
) = with(currentKoinScope()) {
    producePasskeysServiceViewState(
        args = args,
        passKeyService = get(),
    )
}

@Composable
fun producePasskeysServiceViewState(
    args: PasskeysServiceViewDialogRoute.Args,
    passKeyService: PassKeyService,
): Loadable<PasskeysServiceViewState> = produceScreenState(
    key = "passkeys_service_view",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    passkeysServiceViewStateProducer(
        args = args,
    )
}

suspend fun RememberStateFlowScope.passkeysServiceViewStateProducer(
    args: PasskeysServiceViewDialogRoute.Args,
): Flow<Loadable<PasskeysServiceViewState>> {
    val content = PasskeysServiceViewState.Content(
        model = args.model,
    )
    val state = PasskeysServiceViewState(
        content = content
            .right(),
        onClose = {
            navigatePopSelf()
        },
    )
    return flowOf(Loadable.Ok(state))
}
