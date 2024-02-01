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

object JustGetMyDataListRoute : Route {
    fun justGetMyDataActionOrNull(
        translator: TranslatorScope,
        navigate: (NavigationIntent) -> Unit,
    ) = justGetMyDataAction(
        translator = translator,
        navigate = navigate,
    )

    fun justGetMyDataAction(
        translator: TranslatorScope,
        navigate: (NavigationIntent) -> Unit,
    ) = FlatItemAction(
        leading = iconSmall(Icons.Outlined.AccountBox, Icons.Outlined.Dataset),
        title = translator.translate(Res.strings.uri_action_get_my_data_account_title),
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val route = JustGetMyDataListRoute
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )

    @Composable
    override fun Content() {
        JustGetMyDataListScreen()
    }
}
