package com.artemchep.keyguard.feature.justdeleteme.directory

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeServiceInfo
import com.artemchep.keyguard.feature.navigation.DialogRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.iconSmall

data class JustDeleteMeServiceViewRoute(
    val args: Args,
) : DialogRoute {
    companion object {
        fun justDeleteMeActionOrNull(
            translator: TranslatorScope,
            justDeleteMe: JustDeleteMeServiceInfo,
            navigate: (NavigationIntent) -> Unit,
        ) = justDeleteMeAction(
            translator = translator,
            justDeleteMe = justDeleteMe,
            navigate = navigate,
        )

        fun justDeleteMeAction(
            translator: TranslatorScope,
            justDeleteMe: JustDeleteMeServiceInfo,
            navigate: (NavigationIntent) -> Unit,
        ) = FlatItemAction(
            leading = iconSmall(Icons.Outlined.AccountBox, Icons.Outlined.Delete),
            title = translator.translate(Res.strings.uri_action_how_to_delete_account_title),
            onClick = {
                val route = JustDeleteMeServiceViewRoute(
                    args = Args(
                        justDeleteMe = justDeleteMe,
                    ),
                )
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
            },
        )
    }

    data class Args(
        val justDeleteMe: JustDeleteMeServiceInfo,
    )

    @Composable
    override fun Content() {
        JustDeleteMeScreen(
            args = args,
        )
    }
}
