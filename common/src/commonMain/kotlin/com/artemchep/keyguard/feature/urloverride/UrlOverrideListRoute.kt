package com.artemchep.keyguard.feature.urloverride

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

object UrlOverrideListRoute : Route {
    @Composable
    override fun Content() {
        UrlOverrideListScreen()
    }
}
