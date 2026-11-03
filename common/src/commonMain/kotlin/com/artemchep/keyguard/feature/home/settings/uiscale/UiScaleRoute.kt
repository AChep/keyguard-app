package com.artemchep.keyguard.feature.home.settings.uiscale

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRoute

data object UiScaleRoute : DialogRoute {
    @Composable
    override fun Content() = UiScaleScreen()
}
