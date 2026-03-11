package com.artemchep.keyguard.feature.home.vault.quicksearch

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data object QuickSearchAppRoute : Route {
    @Composable
    override fun Content() {
        QuickSearchAppScreen()
    }
}
