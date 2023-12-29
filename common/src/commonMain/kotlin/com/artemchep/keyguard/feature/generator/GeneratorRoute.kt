package com.artemchep.keyguard.feature.generator

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class GeneratorRoute(
    val args: Args = Args(),
) : Route {
    data class Args(
        val username: Boolean = false,
        val password: Boolean = false,
    )

    @Composable
    override fun Content() {
        GeneratorScreen(
            args = args,
        )
    }
}
