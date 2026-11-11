package com.artemchep.keyguard.feature.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp

/**
 * The drag part of the back gesture transform: the leaving screen scales
 * down while the finger drags. The exit part plays after the release: the
 * screen keeps scaling down and fades out.
 */
private const val BACK_GESTURE_DRAG_MILLIS = 120
private const val BACK_GESTURE_EXIT_MILLIS = 160

/** The share of the timeline of a seeked transition that a full drag reaches. */
internal const val BACK_GESTURE_DRAG_FRACTION =
    BACK_GESTURE_DRAG_MILLIS.toFloat() / (BACK_GESTURE_DRAG_MILLIS + BACK_GESTURE_EXIT_MILLIS)

/** The scale of the leaving screen at the end of the drag part. */
private const val BACK_GESTURE_DRAG_SCALE = 0.9f

/**
 * The scale of the leaving screen at the end of the exit part. It keeps
 * shrinking at the speed of the drag part.
 */
private const val BACK_GESTURE_EXIT_SCALE = 0.77f

/** Decelerates, so the screen follows the start of the drag the most. */
private val BackGestureEasing = Easing { fraction ->
    val rest = 1f - fraction
    1f - rest * rest
}

/**
 * Maps the [progress] of a Back gesture to the fraction a seekable
 * navigation transition seeks to. The result is in the drag part of
 * the timeline, so a full drag peeks and never finishes the pop.
 */
fun NavigationAnimation.backGestureFraction(progress: Float): Float {
    val clamped = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
    val fraction = BackGestureEasing.transform(clamped) * BACK_GESTURE_DRAG_FRACTION
    return fraction.coerceIn(0f, 1f)
}

/**
 * Pops a screen with a Back gesture swiped from the [backEdge]. The leaving
 * screen scales down toward the opposite edge while the finger drags, then
 * keeps scaling down toward that edge as it fades out. The returning screen
 * sits under it as it is.
 *
 * The transform only scales: the peek decoration of the leaving screen
 * fades it, see [backGestureFadeSpec]. An alpha on the wrapper of the
 * content would draw it into a buffer of its size and clip the shadow.
 */
fun NavigationAnimation.transformBackGesture(
    scale: Float,
    backEdge: NavigationBackEdge,
): ContentTransform {
    val dragMillis = BACK_GESTURE_DRAG_MILLIS.times(scale).toInt()
    val exitMillis = BACK_GESTURE_EXIT_MILLIS.times(scale).toInt()
    // A swipe from the left edge, or not from an edge,
    // scales the screen toward the right edge.
    val towardRight = backEdge != NavigationBackEdge.RIGHT
    val transformOrigin = TransformOrigin(
        pivotFractionX = if (towardRight) 1f else 0f,
        pivotFractionY = 0.5f,
    )
    // Spans the whole timeline, so it also keeps the
    // leaving content until the end of the exit part.
    return EnterTransition.None togetherWith scaleOut(
        animationSpec = keyframes {
            durationMillis = dragMillis + exitMillis
            BACK_GESTURE_DRAG_SCALE at dragMillis using LinearEasing
        },
        targetScale = BACK_GESTURE_EXIT_SCALE,
        transformOrigin = transformOrigin,
    )
}

/** The look of the leaving screen at the end of the drag part of a back gesture. */
internal val BackGesturePeekCorner = 28.dp
internal val BackGesturePeekElevation = 16.dp
internal val BackGesturePeekMargin = 8.dp

/** The share of the [BackGesturePeekElevation] that is left at the end of the exit part. */
internal const val BACK_GESTURE_SHADOW_EXIT_FACTOR = 0.25f

/** The alpha of the scrim over the returning screen at the start of a back gesture. */
internal const val BACK_GESTURE_SCRIM_ALPHA_DARK = 0.32f
internal const val BACK_GESTURE_SCRIM_ALPHA_LIGHT = 0.16f

/** The share of the scrim alpha that is left at the end of the drag part. */
private const val BACK_GESTURE_SCRIM_DRAG_FACTOR = 0.5f

/**
 * Grows the corners and the margin of the leaving screen over the drag
 * part of [transformBackGesture], as the screen scales down, and holds
 * them through the exit part.
 */
internal fun NavigationAnimation.backGesturePeekSpec(
    scale: Float,
): FiniteAnimationSpec<Float> {
    val dragMillis = BACK_GESTURE_DRAG_MILLIS.times(scale).toInt()
    return tween(
        durationMillis = dragMillis,
        easing = LinearEasing,
    )
}

/**
 * Animates the shadow of the leaving screen, as a factor of the
 * [BackGesturePeekElevation], over the whole timeline of
 * [transformBackGesture]: from zero to the full elevation over the drag
 * part, then down to [BACK_GESTURE_SHADOW_EXIT_FACTOR] of it over the exit
 * part.
 */
internal fun NavigationAnimation.backGestureShadowSpec(
    scale: Float,
): FiniteAnimationSpec<Float> {
    val dragMillis = BACK_GESTURE_DRAG_MILLIS.times(scale).toInt()
    val exitMillis = BACK_GESTURE_EXIT_MILLIS.times(scale).toInt()
    return keyframes {
        durationMillis = dragMillis + exitMillis
        1f at dragMillis using LinearEasing
    }
}

/**
 * Fades the leaving screen of [transformBackGesture] out: it stays opaque
 * over the drag part and fades over the exit part.
 */
internal fun NavigationAnimation.backGestureFadeSpec(
    scale: Float,
): FiniteAnimationSpec<Float> {
    val dragMillis = BACK_GESTURE_DRAG_MILLIS.times(scale).toInt()
    val exitMillis = BACK_GESTURE_EXIT_MILLIS.times(scale).toInt()
    return tween(
        durationMillis = exitMillis,
        delayMillis = dragMillis,
        easing = FastOutLinearInEasing2,
    )
}

/**
 * Clears the scrim over the returning screen over the whole timeline of
 * [transformBackGesture]: from the [alpha] to a half of it over the drag
 * part, so the scrim follows the finger, then to zero over the exit part.
 */
internal fun NavigationAnimation.backGestureScrimSpec(
    scale: Float,
    alpha: Float,
): FiniteAnimationSpec<Float> {
    val dragMillis = BACK_GESTURE_DRAG_MILLIS.times(scale).toInt()
    val exitMillis = BACK_GESTURE_EXIT_MILLIS.times(scale).toInt()
    val dragAlpha = alpha * BACK_GESTURE_SCRIM_DRAG_FACTOR
    return keyframes {
        durationMillis = dragMillis + exitMillis
        dragAlpha at dragMillis using LinearEasing
    }
}
