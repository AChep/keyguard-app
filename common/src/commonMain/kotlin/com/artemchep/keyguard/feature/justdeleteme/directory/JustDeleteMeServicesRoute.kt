package com.artemchep.keyguard.feature.justdeleteme.directory

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.iconSmall

object JustDeleteMeServicesRoute : Route {
    const val ROUTER_NAME = "justdeleteme_services"

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
        leading = iconSmall(Icons.Outlined.AccountBox, Icons.Outlined.Delete),
        title = translator.translate(Res.strings.uri_action_how_to_delete_account_title),
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val route = JustDeleteMeServicesRoute
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )

    @Composable
    override fun Content() {
        TwoFaServicesScreen()
    }
}
