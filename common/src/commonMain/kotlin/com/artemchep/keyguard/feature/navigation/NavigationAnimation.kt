package com.artemchep.keyguard.feature.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.artemchep.keyguard.common.model.NavAnimation

const val ahForward = 300
const val ahBack = 280

const val ah2 = 0.5f

private fun <T> twoot() = tween<T>(
    durationMillis = ahForward,
//    easing = FastOutSlowInEasing,
)

/**
 * Incoming elements are animated using deceleration easing, which starts a transition
 * at peak velocity (the fastest point of an element’s movement) and ends at rest.
 *
 * This is equivalent to the Android `LinearOutSlowInInterpolator`
 */
private val LinearOutSlowInEasing2: Easing = CubicBezierEasing(0.6f, 0.0f, 0.4f, 1.0f)

/**
 * Elements exiting a screen use acceleration easing, where they start at rest and
 * end at peak velocity.
 *
 * This is equivalent to the Android `FastOutLinearInInterpolator`
 */
private val FastOutLinearInEasing2: Easing = CubicBezierEasing(0.6f, 0.0f, 0.4f, 1.0f)

private fun <T> twaat(
    durationMillis: Int,
) = tween<T>(
    durationMillis = durationMillis,
    easing = LinearOutSlowInEasing2,
)

private fun <T> twuut(
    durationMillis: Int,
) = tween<T>(
    durationMillis = durationMillis,
    easing = FastOutLinearInEasing2,
)

object NavigationAnimation

enum class NavigationAnimationType {
    SWITCH,
    GO_FORWARD,
    GO_BACKWARD,
}

fun NavigationAnimation.transform(
    scale: Float,
    animationType: NavAnimation,
    transitionType: NavigationAnimationType,
): ContentTransform = when (animationType) {
    NavAnimation.DISABLED -> transformImmediate()
    NavAnimation.CROSSFADE -> transformSwitchContext(scale)
    NavAnimation.DYNAMIC -> when (transitionType) {
        NavigationAnimationType.SWITCH -> transformSwitchContext(scale)
        NavigationAnimationType.GO_FORWARD -> transformGoForward(scale)
        NavigationAnimationType.GO_BACKWARD -> transformGoBack(scale)
    }
}

fun NavigationAnimation.transformImmediate(): ContentTransform =
    fadeIn(
        animationSpec = snap(),
    ) togetherWith fadeOut(
        animationSpec = snap(),
    )

fun NavigationAnimation.transformSwitchContext(scale: Float): ContentTransform =
    fadeIn(
        animationSpec = tween(ahForward.times(scale).toInt()),
    ) togetherWith fadeOut(
        animationSpec = tween(ahForward.times(scale).toInt()),
    )

fun NavigationAnimation.transformGoForward(scale: Float): ContentTransform =
    slideInHorizontally(
        animationSpec = twaat(ahForward.times(scale).toInt()),
        initialOffsetX = { fullWidth -> fullWidth / 8 },
    ) + fadeIn(
        animationSpec = twaat(ahBack.times(scale).toInt()),
    ) togetherWith slideOutHorizontally(
        animationSpec = twuut(ahForward.times(scale).toInt()),
        targetOffsetX = { fullWidth -> -fullWidth / 12 },
    ) + fadeOut(
        animationSpec = twuut(ahForward.times(scale).toInt() / 2),
    )

fun NavigationAnimation.transformGoBack(scale: Float): ContentTransform =
    slideInHorizontally(
        animationSpec = twaat(ahBack.times(scale).toInt()),
        initialOffsetX = { fullWidth -> -fullWidth / 12 },
    ) + fadeIn(
        animationSpec = twaat(ahBack.times(scale).toInt()),
    ) togetherWith slideOutHorizontally(
        animationSpec = twuut(ahBack.times(scale).toInt()),
        targetOffsetX = { fullWidth -> fullWidth / 8 },
    ) + fadeOut(
        animationSpec = twuut(ahForward.times(scale).toInt() / 2),
    )
