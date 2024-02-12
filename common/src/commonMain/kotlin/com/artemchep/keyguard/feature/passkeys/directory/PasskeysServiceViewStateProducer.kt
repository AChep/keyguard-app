package com.artemchep.keyguard.feature.passkeys.directory

import androidx.compose.runtime.Composable
import arrow.core.right
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.passkey.PassKeyService
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun producePasskeysServiceViewState(
    args: PasskeysServiceViewDialogRoute.Args,
) = with(localDI().direct) {
    producePasskeysServiceViewState(
        args = args,
        passKeyService = instance(),
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
    flowOf(Loadable.Ok(state))
}
