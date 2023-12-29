package com.artemchep.keyguard.feature.duplicates

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.navigation.Route

data class DuplicatesRoute(
    val args: Args = Args(),
) : Route {
    data class Args(
        val filter: DFilter? = null,
    )

    @Composable
    override fun Content() {
        DuplicatesScreen(args)
    }
}
