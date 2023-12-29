package com.artemchep.keyguard.feature.home.vault.collections

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.navigation.Route

data class CollectionsRoute(
    val args: Args,
) : Route {
    data class Args(
        val accountId: AccountId,
        val organizationId: String?,
    )

    @Composable
    override fun Content() {
        CollectionsScreen(
            args = args,
        )
    }
}
