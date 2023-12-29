package com.artemchep.keyguard.feature.home.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.navigation.Route

@Stable
object SettingListRoute : Route {
    @Composable
    override fun Content() {
        SettingListScreen()
    }
}
