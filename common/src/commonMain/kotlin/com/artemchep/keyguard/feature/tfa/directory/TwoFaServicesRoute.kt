package com.artemchep.keyguard.feature.tfa.directory

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.icons.iconSmall

object TwoFaServicesRoute : Route {
    const val ROUTER_NAME = "twofa_services"

    suspend fun actionOrNull(
        translator: TranslatorScope,
        navigate: (NavigationIntent) -> Unit,
    ) = action(
        translator = translator,
        navigate = navigate,
    )

    suspend fun action(
        translator: TranslatorScope,
        navigate: (NavigationIntent) -> Unit,
    ) = FlatItemAction(
        leading = iconSmall(Icons.Outlined.Folder, Icons.Outlined.KeyguardTwoFa),
        title = Res.string.tfa_directory_title.wrap(),
        text = Res.string.tfa_directory_text.wrap(),
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val route = TwoFaServicesRoute
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )

    @Composable
    override fun Content() {
        TwoFaServicesScreen()
    }
}
