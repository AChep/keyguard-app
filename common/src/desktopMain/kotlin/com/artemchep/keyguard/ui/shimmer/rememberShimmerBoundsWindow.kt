package com.artemchep.keyguard.ui.shimmer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import com.artemchep.keyguard.ui.LocalComposeWindow

@Composable
actual fun rememberShimmerBoundsWindow(): Rect {
    val windowBounds = LocalComposeWindow.current.bounds
    return remember(windowBounds) {
        Rect(
            0f,
            0f,
            windowBounds.width.toFloat(),
            windowBounds.height.toFloat(),
        )
    }
}
