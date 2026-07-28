package com.artemchep.keyguard.feature.home.vault.organizations

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

data class OrganizationsRoute(
    val args: Args,
) : Route {
    data class Args(
        val accountId: AccountId,
    )

    override val descriptor get() = RouteDescriptor.Organizations(args.accountId.id)

    @Composable
    override fun Content() {
        FoldersScreen(
            args = args,
        )
    }
}
