package com.artemchep.keyguard.feature.environments.edit

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

/**
 * Creates a new environment, or edits the one with
 * the given [environmentId].
 */
data class EnvironmentEditRoute(
    val environmentId: String? = null,
) : Route {
    override val descriptor get() = RouteDescriptor.EnvironmentEdit(environmentId)

    @Composable
    override fun Content() {
        EnvironmentEditScreen(
            environmentId = environmentId,
        )
    }
}
