package com.artemchep.keyguard.feature.environments.mounts

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

/**
 * Lists the local `.env` files of this device.
 */
object EnvironmentMountsRoute : Route {
    @Composable
    override fun Content() {
        EnvironmentMountsScreen()
    }
}
