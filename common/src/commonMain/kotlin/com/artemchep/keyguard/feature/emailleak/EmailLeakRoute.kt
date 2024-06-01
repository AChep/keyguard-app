package com.artemchep.keyguard.feature.emailleak

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.DialogRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.icon

data class EmailLeakRoute(
    val args: Args,
) : DialogRoute {
    companion object {
        fun checkBreachesEmailActionOrNull(
            translator: TranslatorScope,
            accountId: AccountId,
            email: String,
            navigate: (NavigationIntent) -> Unit,
        ) = checkBreachesEmailAction(
            translator = translator,
            accountId = accountId,
            email = email,
            navigate = navigate,
        )

        fun checkBreachesEmailAction(
            translator: TranslatorScope,
            accountId: AccountId,
            email: String,
            navigate: (NavigationIntent) -> Unit,
        ) = FlatItemAction(
            leading = icon(Icons.AutoMirrored.Outlined.FactCheck),
            title = Res.string.email_action_check_data_breach_title.wrap(),
            onClick = {
                val route = EmailLeakRoute(
                    args = Args(
                        accountId = accountId,
                        email = email,
                    ),
                )
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
            },
        )

        fun checkBreachesUsernameActionOrNull(
            translator: TranslatorScope,
            accountId: AccountId,
            username: String,
            navigate: (NavigationIntent) -> Unit,
        ) = checkBreachesUsernameAction(
            translator = translator,
            accountId = accountId,
            username = username,
            navigate = navigate,
        )

        fun checkBreachesUsernameAction(
            translator: TranslatorScope,
            accountId: AccountId,
            username: String,
            navigate: (NavigationIntent) -> Unit,
        ) = FlatItemAction(
            leading = icon(Icons.AutoMirrored.Outlined.FactCheck),
            title = Res.string.username_action_check_data_breach_title.wrap(),
            onClick = {
                val route = EmailLeakRoute(
                    args = Args(
                        accountId = accountId,
                        email = username,
                    ),
                )
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
            },
        )
    }

    data class Args(
        val accountId: AccountId,
        val email: String,
    )

    @Composable
    override fun Content() {
        EmailLeakScreen(
            args = args,
        )
    }
}
