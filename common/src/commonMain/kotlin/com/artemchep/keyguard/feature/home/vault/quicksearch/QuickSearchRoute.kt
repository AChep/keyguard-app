package com.artemchep.keyguard.feature.home.vault.quicksearch

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data object QuickSearchRoute : Route {
    @Composable
    override fun Content() {
        QuickSearchListScreen(
            activationRevision = LocalQuickSearchActivationRevision.current,
            onDismissRequest = LocalQuickSearchDismiss.current,
        )
    }
}
