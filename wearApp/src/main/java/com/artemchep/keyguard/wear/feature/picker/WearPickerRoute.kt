package com.artemchep.keyguard.wear.feature.picker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.navigation.DialogRoute
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.ui.ContextItem

@Stable
data class WearPickerRoute(
    val actions: List<ContextItem>,
) : DialogRoute {
    @Composable
    override fun Content() {
        WearPickerScreen(
            items = actions,
        )
    }
}
