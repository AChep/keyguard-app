package com.artemchep.keyguard.feature.equivalentdomains

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

data class EquivalentDomainsRoute(
    val args: Args,
) : Route {
    data class Args(
        val accountId: AccountId,
    )

    override val descriptor get() = RouteDescriptor.EquivalentDomains(args.accountId.id)

    @Composable
    override fun Content() {
        EquivalentDomainsScreen(
            args = args,
        )
    }
}
