package com.artemchep.keyguard.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.ui.theme.GlobalExpressive
import com.artemchep.keyguard.ui.theme.LocalExpressive

data class AccountViewRoute(
    val accountId: AccountId,
) : Route {
    @Composable
    override fun Content() {
        CompositionLocalProvider(
            LocalExpressive provides GlobalExpressive.current,
        ) {
            AccountViewScreen(
                accountId = accountId,
            )
        }
    }
}
