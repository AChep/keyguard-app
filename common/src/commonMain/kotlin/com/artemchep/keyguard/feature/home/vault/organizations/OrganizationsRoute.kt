package com.artemchep.keyguard.feature.home.vault.organizations

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.navigation.Route

data class OrganizationsRoute(
    val args: Args,
) : Route {
    data class Args(
        val accountId: AccountId,
    )

    @Composable
    override fun Content() {
        FoldersScreen(
            args = args,
        )
    }
}
