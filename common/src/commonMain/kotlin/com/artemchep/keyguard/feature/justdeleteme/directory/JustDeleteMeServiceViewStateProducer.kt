package com.artemchep.keyguard.feature.justdeleteme.directory

import androidx.compose.runtime.Composable
import arrow.core.right
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.CheckUsernameLeak
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceJustDeleteMeServiceViewState(
    args: JustDeleteMeServiceViewRoute.Args,
) = with(localDI().direct) {
    produceJustDeleteMeServiceViewState(
        args = args,
        checkUsernameLeak = instance(),
        dateFormatter = instance(),
    )
}

@Composable
fun produceJustDeleteMeServiceViewState(
    args: JustDeleteMeServiceViewRoute.Args,
    checkUsernameLeak: CheckUsernameLeak,
    dateFormatter: DateFormatter,
): Loadable<JustDeleteMeServiceViewState> = produceScreenState(
    key = "justdeleteme_service_view",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val content =
        JustDeleteMeServiceViewState.Content(
            breaches = persistentListOf(),
        ).right()
    val state = JustDeleteMeServiceViewState(
        content = content,
        onClose = {
            navigatePopSelf()
        },
    )
    flowOf(Loadable.Ok(state))
}
