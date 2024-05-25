package com.artemchep.keyguard.feature.justdeleteme.directory

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeServiceInfo
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.DialogRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.iconSmall

data class JustDeleteMeServiceViewDialogRoute(
    val args: Args,
) : DialogRoute {
    companion object {
        fun actionOrNull(
            translator: TranslatorScope,
            justDeleteMe: JustDeleteMeServiceInfo,
            navigate: (NavigationIntent) -> Unit,
        ) = action(
            translator = translator,
            justDeleteMe = justDeleteMe,
            navigate = navigate,
        )

        fun action(
            translator: TranslatorScope,
            justDeleteMe: JustDeleteMeServiceInfo,
            navigate: (NavigationIntent) -> Unit,
        ) = FlatItemAction(
            leading = iconSmall(Icons.Outlined.AccountBox, Icons.Outlined.Delete),
            title = Res.string.uri_action_how_to_delete_account_title.wrap(),
            onClick = {
                val route = JustDeleteMeServiceViewDialogRoute(
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
        JustDeleteMeDialogScreen(
            args = args,
        )
    }
}
