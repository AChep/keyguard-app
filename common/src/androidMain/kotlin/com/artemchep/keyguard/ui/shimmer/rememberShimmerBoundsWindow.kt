package com.artemchep.keyguard.ui.shimmer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberShimmerBoundsWindow(): Rect {
    val displayMetrics = LocalContext.current.resources.displayMetrics
    return remember(displayMetrics) {
        Rect(
            0f,
            0f,
            displayMetrics.widthPixels.toFloat(),
            displayMetrics.heightPixels.toFloat(),
        )
    }
}
