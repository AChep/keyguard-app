package com.artemchep.keyguard.ui.toolbar.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import com.artemchep.keyguard.ui.surface.LocalBackgroundManager
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor

object ToolbarColors {
    @Composable
    fun containerColor(): Color = LocalSurfaceColor.current

    @Composable
    fun scrolledContainerColor(
        containerColor: Color,
    ): Color {
        if (
            containerColor == Color.Transparent ||
            containerColor.isUnspecified
        ) {
            return containerColor
        }

        return LocalBackgroundManager.current.colorHighest
    }
}
