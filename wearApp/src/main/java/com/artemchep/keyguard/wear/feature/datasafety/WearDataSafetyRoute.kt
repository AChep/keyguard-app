package com.artemchep.keyguard.wear.feature.datasafety

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.navigation.Route

@Stable
object WearDataSafetyRoute : Route {
    @Composable
    override fun Content() {
        WearDataSafetyScreen()
    }
}
