package com.artemchep.keyguard.feature.generator.wordlist.list

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

object WordlistListRoute : Route {
    override val descriptor get() = RouteDescriptor.WordlistList

    @Composable
    override fun Content() {
        WordlistListScreen()
    }
}
