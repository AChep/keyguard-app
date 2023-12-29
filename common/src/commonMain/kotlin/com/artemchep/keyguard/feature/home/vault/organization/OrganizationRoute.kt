package com.artemchep.keyguard.feature.home.vault.organization

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRoute

data class OrganizationRoute(
    val args: Args,
) : DialogRoute {
    data class Args(
        val organizationId: String,
    )

    @Composable
    override fun Content() {
        OrganizationScreen(
            args = args,
        )
    }
}
