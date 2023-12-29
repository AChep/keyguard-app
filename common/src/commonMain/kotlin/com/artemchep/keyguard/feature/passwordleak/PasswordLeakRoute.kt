package com.artemchep.keyguard.feature.passwordleak

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.icon

data class PasswordLeakRoute(
    val args: Args,
) : DialogRoute {
    companion object {
        fun checkBreachesPasswordActionOrNull(
            translator: TranslatorScope,
            password: String,
            navigate: (NavigationIntent) -> Unit,
        ) = checkBreachesPasswordAction(
            translator = translator,
            password = password,
            navigate = navigate,
        )

        fun checkBreachesPasswordAction(
            translator: TranslatorScope,
            password: String,
            navigate: (NavigationIntent) -> Unit,
        ) = FlatItemAction(
            leading = icon(Icons.Outlined.FactCheck),
            title = translator.translate(Res.strings.password_action_check_data_breach_title),
            onClick = {
                val route = PasswordLeakRoute(
                    args = Args(
                        password = password,
                    ),
                )
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
            },
        )
    }

    data class Args(
        val password: String,
    )

    @Composable
    override fun Content() {
        PasswordLeakScreen(
            args = args,
        )
    }
}
