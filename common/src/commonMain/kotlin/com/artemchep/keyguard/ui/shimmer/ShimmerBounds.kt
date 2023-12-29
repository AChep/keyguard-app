package com.artemchep.keyguard.ui.shimmer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect

@Composable
expect fun rememberShimmerBoundsWindow(): Rect

@Composable
internal fun rememberShimmerBounds(
    shimmerBounds: ShimmerBounds,
): Rect? {
    val windowBounds = rememberShimmerBoundsWindow()
    return remember(shimmerBounds, windowBounds) {
        when (shimmerBounds) {
            ShimmerBounds.Window -> windowBounds
            ShimmerBounds.Custom -> Rect.Zero
            ShimmerBounds.View -> null
        }
    }
}

sealed class ShimmerBounds {
    data object Custom : ShimmerBounds()
    data object View : ShimmerBounds()
    data object Window : ShimmerBounds()
}
