package com.artemchep.keyguard.feature.environments.mount

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRoute

/**
 * Creates a local `.env` file for an environment. Asks for
 * the environment unless [environmentId] is given.
 */
data class EnvironmentMountRoute(
    val environmentId: String? = null,
) : DialogRoute {
    @Composable
    override fun Content() {
        EnvironmentMountScreen(
            environmentId = environmentId,
        )
    }
}
