package com.artemchep.keyguard.feature.gpgagent.tools

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

internal object GpgToolsPickerRoute : Route {
    @Composable
    override fun Content() {
        GpgToolsPickerScreen()
    }
}
