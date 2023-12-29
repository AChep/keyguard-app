package com.artemchep.keyguard.feature.websiteleak

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.icon

data class WebsiteLeakRoute(
    val args: Args,
) : DialogRoute {
    companion object {
        fun checkBreachesWebsiteActionOrNull(
            translator: TranslatorScope,
            host: String,
            navigate: (NavigationIntent) -> Unit,
        ) = checkBreachesWebsiteAction(
            translator = translator,
            host = host,
            navigate = navigate,
        )

        fun checkBreachesWebsiteAction(
            translator: TranslatorScope,
            host: String,
            navigate: (NavigationIntent) -> Unit,
        ) = FlatItemAction(
            leading = icon(Icons.Outlined.FactCheck),
            title = translator.translate(Res.strings.website_action_check_data_breach_title),
            onClick = {
                val route = WebsiteLeakRoute(
                    args = Args(
                        host = host,
                    ),
                )
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
            },
        )
    }

    data class Args(
        val host: String,
    )

    @Composable
    override fun Content() {
        WebsiteLeakScreen(
            args = args,
        )
    }
}
