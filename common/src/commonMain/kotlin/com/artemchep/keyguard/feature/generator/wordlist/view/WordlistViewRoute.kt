package com.artemchep.keyguard.feature.generator.wordlist.view

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

data class WordlistViewRoute(
    val args: Args,
) : Route {
    data class Args(
        val wordlistId: Long,
    )

    override val descriptor get() = RouteDescriptor.WordlistView(args.wordlistId)

    @Composable
    override fun Content() {
        WordlistViewScreen(args)
    }
}
