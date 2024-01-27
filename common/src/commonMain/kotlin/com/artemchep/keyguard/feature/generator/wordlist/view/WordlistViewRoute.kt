package com.artemchep.keyguard.feature.generator.wordlist.view

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class WordlistViewRoute(
    val args: Args,
) : Route {
    data class Args(
        val wordlistId: Long,
    )

    @Composable
    override fun Content() {
        WordlistViewScreen(args)
    }
}
