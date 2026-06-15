package com.artemchep.keyguard.wear.feature.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.navigation.Route

@Stable
data class WearAccountViewRoute(
    val accountId: AccountId,
) : Route {
    @Composable
    override fun Content() {
        AccountViewScreen(
            accountId = accountId,
        )
    }
}
