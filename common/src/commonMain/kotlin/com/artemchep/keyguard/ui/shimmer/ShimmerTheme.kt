package com.artemchep.keyguard.ui.shimmer

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.HighEmphasisAlpha

data class ShimmerTheme(

    /**
     * The [AnimationSpec] which will be used for the traversal. Use an infinite spec to repeat
     * shimmering.
     *
     * @see defaultShimmerTheme
     */
    val animationSpec: AnimationSpec<Float>,

    /**
     * The [BlendMode] used in the shimmer's [androidx.compose.ui.graphics.Paint]. Have a look at
     * the theming samples to get an idea on how to utilize the blend mode.
     *
     * @see ThemingSamples in the sample app.
     */
    val blendMode: BlendMode,

    /**
     * Describes the orientation of the shimmer in degrees. Zero is thereby defined as shimmer
     * traversing from the left to the right. The rotation is applied clockwise.
     * Only values >= 0 will be accepted.
     */
    val rotation: Float,

    /**
     * The [shaderColors] can be used to control the colors and alpha values of the shimmer. The
     * size of the list has to be kept in sync with the [shaderColorStops]. Consult the docs of the
     * [androidx.compose.ui.graphics.LinearGradientShader] for more information and have a look
     * at the theming samples.
     *
     * @see ThemingSamples in the samples app.
     */
    val shaderColors: List<Color>,

    /**
     * See docs of [shaderColors].
     */
    val shaderColorStops: List<Float>?,

    /**
     * Controls the width used to distribute the [shaderColors].
     */
    val shimmerWidth: Dp,
)

val defaultShimmerTheme: ShimmerTheme = ShimmerTheme(
    animationSpec = infiniteRepeatable(
        animation = tween(
            800,
            easing = LinearEasing,
            delayMillis = 400,
        ),
        repeatMode = RepeatMode.Restart,
    ),
    blendMode = BlendMode.DstIn,
    rotation = 5.0f,
    shaderColors = listOf(
        Color.Unspecified.copy(alpha = DisabledEmphasisAlpha),
        Color.Unspecified.copy(alpha = HighEmphasisAlpha),
        Color.Unspecified.copy(alpha = DisabledEmphasisAlpha),
    ),
    shaderColorStops = listOf(
        0.0f,
        0.5f,
        1.0f,
    ),
    shimmerWidth = 400.dp,
)

val LocalShimmerTheme = staticCompositionLocalOf { defaultShimmerTheme }
