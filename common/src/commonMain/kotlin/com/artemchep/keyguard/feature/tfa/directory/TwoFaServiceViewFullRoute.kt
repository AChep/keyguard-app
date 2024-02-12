package com.artemchep.keyguard.feature.tfa.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class TwoFaServiceViewFullRoute(
    val args: TwoFaServiceViewDialogRoute.Args,
) : Route {
    @Composable
    override fun Content() {
        TwoFaServiceViewFullScreen(
            args = args,
        )
    }
}
