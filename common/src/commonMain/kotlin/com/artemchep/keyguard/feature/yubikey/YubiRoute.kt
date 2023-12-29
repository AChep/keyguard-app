package com.artemchep.keyguard.feature.yubikey

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

object YubiRoute : Route {
    @Composable
    override fun Content() {
        YubiScreen()
    }
}
