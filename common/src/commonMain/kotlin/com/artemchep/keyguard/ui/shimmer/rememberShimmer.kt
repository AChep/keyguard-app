package com.artemchep.keyguard.ui.shimmer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun rememberShimmer(
    shimmerBounds: ShimmerBounds,
    theme: ShimmerTheme = LocalShimmerTheme.current,
): Shimmer {
    val effect = rememberShimmerEffect(theme)
    val bounds = rememberShimmerBounds(shimmerBounds)

    return remember(theme, effect, bounds) {
        Shimmer(theme, effect, bounds)
    }
}

class Shimmer internal constructor(
    internal val theme: ShimmerTheme,
    internal val effect: ShimmerEffect,
    bounds: Rect?,
) {

    internal val boundsFlow = MutableStateFlow(bounds)

    fun updateBounds(bounds: Rect?) {
        boundsFlow.value = bounds
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Shimmer

        if (theme != other.theme) return false
        if (effect != other.effect) return false

        return true
    }

    override fun hashCode(): Int {
        var result = theme.hashCode()
        result = 31 * result + effect.hashCode()
        return result
    }
}
