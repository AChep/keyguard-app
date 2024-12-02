package com.artemchep.keyguard.feature.equivalentdomains

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.navigation.Route

data class EquivalentDomainsRoute(
    val args: Args,
) : Route {
    data class Args(
        val accountId: AccountId,
    )

    @Composable
    override fun Content() {
        EquivalentDomainsScreen(
            args = args,
        )
    }
}
