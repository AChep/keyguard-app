package com.artemchep.keyguard.feature.auth

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.navigation.Route

data class AccountViewRoute(
    val accountId: AccountId,
) : Route {
    @Composable
    override fun Content() {
        AccountViewScreen(
            accountId = accountId,
        )
    }
}
