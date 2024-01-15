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

object JustDeleteMeServiceListRoute : Route {
    fun justDeleteMeActionOrNull(
        translator: TranslatorScope,
        navigate: (NavigationIntent) -> Unit,
    ) = justDeleteMeAction(
        translator = translator,
        navigate = navigate,
    )

    fun justDeleteMeAction(
        translator: TranslatorScope,
        navigate: (NavigationIntent) -> Unit,
    ) = FlatItemAction(
        leading = iconSmall(Icons.Outlined.AccountBox, Icons.Outlined.Delete),
        title = translator.translate(Res.strings.uri_action_how_to_delete_account_title),
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val route = JustDeleteMeServiceListRoute
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )

    @Composable
    override fun Content() {
        JustDeleteMeListScreen()
    }
}
