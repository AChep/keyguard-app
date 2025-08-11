package com.artemchep.keyguard.feature.tfa.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

object TwoFaServiceListRoute : Route {
    @Composable
    override fun Content() {
        TwoFaServiceListScreen()
    }
}
