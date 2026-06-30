package com.artemchep.keyguard.feature.generator.history

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

object GeneratorHistoryRoute : Route {
    override val descriptor get() = RouteDescriptor.GeneratorHistory

    @Composable
    override fun Content() {
        GeneratorHistoryScreen()
    }
}
