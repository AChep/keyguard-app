package com.artemchep.keyguard.feature.home.settings.shortcuts

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.feature.navigation.DialogRoute

data class ShortcutRecorderRoute(
    val action: GlobalShortcutAction,
) : DialogRoute {
    @Composable
    override fun Content() = ShortcutRecorderScreen(action)
}
