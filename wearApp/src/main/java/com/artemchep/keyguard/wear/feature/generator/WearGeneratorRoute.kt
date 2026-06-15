package com.artemchep.keyguard.wear.feature.generator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.navigation.Route

@Stable
data class WearGeneratorRoute(
    val args: GeneratorRoute.Args = wearGeneratorArgs(),
) : Route {
    @Composable
    override fun Content() {
        WearGeneratorScreen(
            args = args,
        )
    }
}
