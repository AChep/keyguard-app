package com.artemchep.keyguard.feature.justgetdata.directory

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Dataset
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.iconSmall

object JustGetMyDataServicesRoute : Route {
    const val ROUTER_NAME = "justgetmydata_services"

    fun actionOrNull(
        translator: TranslatorScope,
        navigate: (NavigationIntent) -> Unit,
    ) = action(
        translator = translator,
        navigate = navigate,
    )

    fun action(
        translator: TranslatorScope,
        navigate: (NavigationIntent) -> Unit,
    ) = FlatItemAction(
        leading = iconSmall(Icons.Outlined.AccountBox, Icons.Outlined.Dataset),
        title = translator.translate(Res.strings.uri_action_get_my_data_account_title),
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val route = JustGetMyDataServicesRoute
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )

    @Composable
    override fun Content() {
        JustGetMyDataServicesScreen()
    }
}
