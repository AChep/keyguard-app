package com.artemchep.keyguard.feature.duplicates.list

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.duplicates.DuplicatesRoute
import com.artemchep.keyguard.feature.navigation.Route

data class DuplicatesListRoute(
    val args: DuplicatesRoute.Args,
) : Route {
    @Composable
    override fun Content() {
        DuplicatesListScreen(args)
    }
}
