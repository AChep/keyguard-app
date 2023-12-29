package com.artemchep.keyguard.feature.generator.emailrelay

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ForwardToInbox
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.iconSmall

object EmailRelayListRoute : Route {
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
        leading = iconSmall(Icons.Outlined.ForwardToInbox),
        title = translator.translate(Res.strings.emailrelay_list_header_title),
        onClick = {
            val route = EmailRelayListRoute
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )

    @Composable
    override fun Content() {
        EmailRelayListScreen()
    }
}
