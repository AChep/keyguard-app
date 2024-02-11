package com.artemchep.keyguard.ui.toolbar.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.component.surfaceColorAtElevationSemi
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

        val tint = MaterialTheme.colorScheme
            .surfaceColorAtElevationSemi(elevation = 2.0.dp)
        return tint.compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
    }
}
