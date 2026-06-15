package com.artemchep.keyguard.wear.feature.value

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.ui.ContextItem

@Stable
data class WearValueViewRoute(
    val title: String?,
    val value: String,
    val visibility: Visibility,
    val monospace: Boolean,
    val colorize: Boolean,
    val actions: List<ContextItem>,
) : Route {
    @Composable
    override fun Content() {
        WearValueViewScreen(
            title = title,
            value = value,
            visibility = visibility,
            monospace = monospace,
            colorize = colorize,
            actions = actions,
        )
    }
}
