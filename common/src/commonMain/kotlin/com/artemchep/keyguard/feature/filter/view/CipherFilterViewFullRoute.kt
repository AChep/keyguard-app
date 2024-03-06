package com.artemchep.keyguard.feature.filter.view

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class CipherFilterViewFullRoute(
    val args: CipherFilterViewDialogRoute.Args,
) : Route {
    @Composable
    override fun Content() {
        CipherFilterViewFullScreen(
            args = args,
        )
    }
}
