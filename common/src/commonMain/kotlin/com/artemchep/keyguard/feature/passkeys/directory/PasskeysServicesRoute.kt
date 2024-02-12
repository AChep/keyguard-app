package com.artemchep.keyguard.feature.passkeys.directory

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.iconSmall

object PasskeysServicesRoute : Route {
    const val ROUTER_NAME = "passkeys_services"

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
        leading = iconSmall(Icons.Outlined.Folder, Icons.Outlined.Key),
        title = translator.translate(Res.strings.passkeys_directory_title),
        text = translator.translate(Res.strings.passkeys_directory_text),
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val route = PasskeysServicesRoute
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )

    @Composable
    override fun Content() {
        TwoFaServicesScreen()
    }
}
