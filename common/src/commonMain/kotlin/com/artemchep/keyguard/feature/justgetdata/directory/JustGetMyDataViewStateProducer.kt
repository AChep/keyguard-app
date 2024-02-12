package com.artemchep.keyguard.feature.justgetdata.directory

import androidx.compose.runtime.Composable
import arrow.core.right
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.compose.localDI
import org.kodein.di.direct

@Composable
fun produceJustGetMyDataViewState(
    args: JustGetMyDataViewDialogRoute.Args,
) = with(localDI().direct) {
    produceJustGetMyDataViewState(
        unit = Unit,
        args = args,
    )
}

@Composable
fun produceJustGetMyDataViewState(
    unit: Unit,
    args: JustGetMyDataViewDialogRoute.Args,
): Loadable<JustGetMyDataViewState> = produceScreenState(
    key = "justgetmydata_view",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val content = JustGetMyDataViewState.Content(
        model = args.model,
    )
    val state = JustGetMyDataViewState(
        content = content
            .right(),
        onClose = {
            navigatePopSelf()
        },
    )
    flowOf(Loadable.Ok(state))
}
