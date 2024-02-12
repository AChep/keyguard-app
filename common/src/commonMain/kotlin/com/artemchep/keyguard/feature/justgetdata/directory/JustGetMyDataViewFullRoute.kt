package com.artemchep.keyguard.feature.justgetdata.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class JustGetMyDataViewFullRoute(
    val args: JustGetMyDataViewDialogRoute.Args,
) : Route {
    @Composable
    override fun Content() {
        JustGetMyDataViewFullScreen(
            args = args,
        )
    }
}
