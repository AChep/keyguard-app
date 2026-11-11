package com.artemchep.keyguard.feature.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sign

private const val BACK_GESTURE_DRAG_MILLIS = 120
private const val BACK_GESTURE_COMMIT_MILLIS = 450
private const val BACK_GESTURE_DURATION_MILLIS = BACK_GESTURE_DRAG_MILLIS + BACK_GESTURE_COMMIT_MILLIS
internal const val BACK_GESTURE_DRAG_FRACTION =
    BACK_GESTURE_DRAG_MILLIS.toFloat() / BACK_GESTURE_DURATION_MILLIS
private const val BACK_GESTURE_SCALE_REDUCTION = 0.15f
private const val BACK_GESTURE_FADE_MULTIPLIER = 5f

/** The share of a crossfade that a full drag reaches. */
private const val BACK_GESTURE_CROSSFADE_FRACTION = 3f / 7f
private const val BACK_GESTURE_EMPHASIZED_TIME_SPLIT = 1f / 6f
private const val BACK_GESTURE_EMPHASIZED_POSITION_SPLIT = 0.4f

internal val BackGesturePeekCorner = 28.dp
internal val BackGesturePeekMargin = 8.dp
internal val BackGestureEnteringOffset = 96.dp
internal const val BACK_GESTURE_SCRIM_ALPHA_DARK = 0.8f
internal const val BACK_GESTURE_SCRIM_ALPHA_LIGHT = 0.2f

private val BackGestureEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/** Decelerates, so the motion follows the start of the drag the most. */
private val BackGestureDecelerateEasing = Easing { fraction ->
    val rest = 1f - fraction
    1f - rest * rest
}

// WindowManager Shell's emphasized path, expressed as two normalized Beziers
// so the motion model does not need an initialized platform graphics backend.
private val BackGestureCommitAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private val BackGestureCommitDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val BackGestureCommitEasing = Easing { fraction ->
    if (fraction < BACK_GESTURE_EMPHASIZED_TIME_SPLIT) {
        BACK_GESTURE_EMPHASIZED_POSITION_SPLIT * BackGestureCommitAccelerate.transform(
            fraction / BACK_GESTURE_EMPHASIZED_TIME_SPLIT,
        )
    } else {
        BACK_GESTURE_EMPHASIZED_POSITION_SPLIT +
            (1f - BACK_GESTURE_EMPHASIZED_POSITION_SPLIT) * BackGestureCommitDecelerate.transform(
                (fraction - BACK_GESTURE_EMPHASIZED_TIME_SPLIT) / (1f - BACK_GESTURE_EMPHASIZED_TIME_SPLIT),
            )
    }
}

/** A full drag previews the previous page without finishing the pop. */
fun NavigationAnimation.backGestureFraction(progress: Float): Float =
    BackGestureEasing.transform(progress.normalized()) * BACK_GESTURE_DRAG_FRACTION

/** Same, for a gesture that crossfades: a tab switch, or the crossfade preference. */
fun NavigationAnimation.backGestureCrossfadeFraction(progress: Float): Float =
    BackGestureDecelerateEasing.transform(progress.normalized()) * BACK_GESTURE_CROSSFADE_FRACTION

/** [NavigationPeekBox] draws the geometry; its peek fraction gives the segment its timeline. */
fun NavigationAnimation.transformBackGesture(): ContentTransform =
    EnterTransition.None togetherWith ExitTransition.None

/**
 * The timeline of the peek fraction. It is linear and the longest animation
 * of its segment, so the fraction equals the one of the seekable transition
 * that [NavigationBackMotion.commit] records.
 */
internal fun NavigationAnimation.backGestureSpec(scale: Float): FiniteAnimationSpec<Float> = tween(
    durationMillis = (BACK_GESTURE_DURATION_MILLIS * scale).toInt(),
    easing = LinearEasing,
)

/** Plays the rest of the seek after the release. Linear: [NavigationBackMotion] eases the commit. */
internal fun NavigationAnimation.backGestureCommitSpec(scale: Float): FiniteAnimationSpec<Float> = tween(
    durationMillis = (BACK_GESTURE_COMMIT_MILLIS * scale).toInt(),
    easing = LinearEasing,
)

/** The actual seek position and touch displacement at release, including short drags. */
private data class NavigationBackRelease(
    val fraction: Float,
    val touchDeltaY: Float,
)

@Stable
internal class NavigationBackMotion(
    private val gesture: NavigationBackGesture,
) {
    private var release: NavigationBackRelease? by mutableStateOf(null)

    fun commit(fraction: Float) {
        release = NavigationBackRelease(fraction.normalized(), gesture.touchDeltaY)
    }

    /** The progress of the commit that plays after the release, zero during the drag. */
    fun commitProgress(fraction: Float): Float {
        val release = release ?: return 0f
        val commitFraction = ((fraction - release.fraction) / (1f - release.fraction)).normalized()
        // Match ValueAnimator's default accelerate/decelerate interpolator.
        return ((1.0 - cos(PI * commitFraction)) / 2.0).toFloat()
    }

    fun frame(fraction: Float): NavigationBackFrame {
        val release = release
        val dragFraction = release?.fraction ?: fraction
        return NavigationBackFrame(
            progress = (dragFraction / BACK_GESTURE_DRAG_FRACTION).normalized(),
            touchDeltaY = release?.touchDeltaY ?: gesture.touchDeltaY,
            swipeEdge = gesture.swipeEdge,
            commitProgress = commitProgress(fraction),
        )
    }
}

@Immutable
internal data class NavigationBackFrame(
    val progress: Float,
    val touchDeltaY: Float,
    val swipeEdge: NavigationBackEdge,
    val commitProgress: Float,
) {
    fun transform(
        size: Size,
        margin: Float,
        enteringOffset: Float,
        entering: Boolean,
    ): NavigationBackTransform {
        val reduction = BACK_GESTURE_SCALE_REDUCTION * progress
        // The commit moves the page from the pose of the drag to its pose at rest.
        val geometryProgress = BackGestureCommitEasing.transform(commitProgress)
        val dragX = when {
            entering -> -max(size.width * BACK_GESTURE_SCALE_REDUCTION, enteringOffset)
            swipeEdge == NavigationBackEdge.RIGHT -> 0f
            else -> (size.width * BACK_GESTURE_SCALE_REDUCTION / 2f - margin) * progress
        }
        val restX = if (entering) 0f else dragX + size.width * reduction / 2f + enteringOffset
        return NavigationBackTransform(
            scale = lerp(1f - reduction, 1f, geometryProgress),
            offset = Offset(
                x = lerp(dragX, restX, geometryProgress),
                y = lerp(verticalOffset(size.height, reduction, margin), 0f, geometryProgress),
            ),
            alpha = if (entering) 1f else (1f - commitProgress * BACK_GESTURE_FADE_MULTIPLIER).normalized(),
            corner = if (entering) progress * (1f - geometryProgress) else progress,
        )
    }

    private fun verticalOffset(height: Float, reduction: Float, margin: Float): Float {
        if (height <= 0f || !touchDeltaY.isFinite()) return 0f
        val ratio = (abs(touchDeltaY) / (height / 2f)).normalized()
        val travel = max(0f, height * reduction / 2f - margin)
        return sign(touchDeltaY) * travel * BackGestureDecelerateEasing.transform(ratio)
    }
}

@Immutable
internal data class NavigationBackTransform(
    val scale: Float,
    val offset: Offset,
    val alpha: Float,
    val corner: Float,
)

private fun Float.normalized(): Float = if (isNaN()) 0f else coerceIn(0f, 1f)
