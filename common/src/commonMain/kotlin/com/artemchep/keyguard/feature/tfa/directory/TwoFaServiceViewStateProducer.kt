package com.artemchep.keyguard.feature.tfa.directory

import androidx.compose.runtime.Composable
import arrow.core.right
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.twofa.TwoFaService
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceTwoFaServiceViewState(
    args: TwoFaServiceViewDialogRoute.Args,
) = with(localDI().direct) {
    produceTwoFaServiceViewState(
        args = args,
        twoFaService = instance(),
    )
}

@Composable
fun produceTwoFaServiceViewState(
    args: TwoFaServiceViewDialogRoute.Args,
    twoFaService: TwoFaService,
): Loadable<TwoFaServiceViewState> = produceScreenState(
    key = "tfa_service_view",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    twoFaServiceViewStateProducer(
        args = args,
    )
}

suspend fun RememberStateFlowScope.twoFaServiceViewStateProducer(
    args: TwoFaServiceViewDialogRoute.Args,
): Flow<Loadable<TwoFaServiceViewState>> {
    val content = TwoFaServiceViewState.Content(
        model = args.model,
    )
    val state = TwoFaServiceViewState(
        content = content
            .right(),
        onClose = {
            navigatePopSelf()
        },
    )
    return flowOf(Loadable.Ok(state))
}
