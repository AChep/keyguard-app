package com.artemchep.keyguard.ui.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalSurfaceColor = staticCompositionLocalOf {
    Color.White
}

@Composable
inline fun ProvideSurfaceColor(
    color: Color,
    crossinline content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSurfaceColor provides color,
    ) {
        content()
    }
}
