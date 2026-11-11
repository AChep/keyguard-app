package com.artemchep.keyguard.feature.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.artemchep.keyguard.ui.theme.isDark

/** Plain holder, only the effect of [NavigationPeekBox] writes it. */
private class NavigationPeekTopMemory<S> {
    var peek: NavigationSeek<S>? = null
}

/**
 * Moves both pages during a predictive stack pop. Geometry reads the seek
 * fraction in the graphics layer, so finger movement does not relayout content.
 * The scrim of the returning page is drawn outside of that layer, across the
 * whole pane.
 */
@Composable
internal fun <S> AnimatedVisibilityScope.NavigationPeekBox(
    state: S,
    peek: NavigationSeek<S>?,
    scale: Float,
    content: @Composable () -> Unit,
) {
    val memory = remember { NavigationPeekTopMemory<S>() }
    val topPeek = if (peek != null && state == peek.targetState) {
        peek
    } else {
        memory.peek?.takeIf { transition.targetState == EnterExitState.PostExit }
    }
    SideEffect { memory.peek = topPeek }
    val isUnder = peek != null && state == peek.backState
    val motion = (topPeek ?: peek?.takeIf { isUnder })?.motion
    val enterFraction = navigationPeekFraction(enabled = motion != null, scale = scale, entering = true)
    val leaveFraction = navigationPeekFraction(enabled = motion != null, scale = scale, entering = false)
    val fraction = if (isUnder) enterFraction else leaveFraction
    val colorScheme = MaterialTheme.colorScheme
    val scrimAlpha = if (colorScheme.isDark) {
        BACK_GESTURE_SCRIM_ALPHA_DARK
    } else {
        BACK_GESTURE_SCRIM_ALPHA_LIGHT
    }
    val modifier = if (motion != null) {
        val scrimModifier = if (isUnder) {
            Modifier.navigationPeekScrim(motion, fraction, colorScheme.scrim, scrimAlpha)
        } else {
            Modifier
        }
        // The scrim is before the layer of the page, so it stays untransformed.
        scrimModifier.navigationPeekPage(motion, fraction, isUnder)
    } else {
        Modifier
    }
    Box(modifier = modifier, propagateMinConstraints = true) {
        content()
    }
}

/**
 * The fraction of the motion of a page that enters, or of one that leaves.
 * The value of a state is fixed, only the spec depends on the peek: a
 * transition at rest does not apply a new value of the state it is in, so
 * the next peek of the page would start from a stale fraction.
 */
@Composable
private fun AnimatedVisibilityScope.navigationPeekFraction(
    enabled: Boolean,
    scale: Float,
    entering: Boolean,
): State<Float> = transition.animateFloat(
    transitionSpec = {
        if (enabled) NavigationAnimation.backGestureSpec(scale) else snap()
    },
    label = if (entering) "NavigationPeekEnter" else "NavigationPeekLeave",
) { enterExit ->
    if (entering) {
        if (enterExit == EnterExitState.PreEnter) 0f else 1f
    } else {
        if (enterExit == EnterExitState.PostExit) 1f else 0f
    }
}

private fun Modifier.navigationPeekPage(
    motion: NavigationBackMotion,
    fraction: State<Float>,
    entering: Boolean,
): Modifier = graphicsLayer {
    val transform = motion.frame(fraction.value).transform(
        size = size,
        margin = BackGesturePeekMargin.toPx(),
        enteringOffset = BackGestureEnteringOffset.toPx(),
        entering = entering,
    )
    scaleX = transform.scale
    scaleY = transform.scale
    translationX = transform.offset.x
    translationY = transform.offset.y
    shape = RoundedCornerShape(BackGesturePeekCorner.toPx() * transform.corner)
    clip = true
    alpha = transform.alpha
}

private fun Modifier.navigationPeekScrim(
    motion: NavigationBackMotion,
    fraction: State<Float>,
    color: Color,
    alpha: Float,
): Modifier = drawWithContent {
    drawContent()
    drawRect(color = color, alpha = alpha * (1f - motion.commitProgress(fraction.value)))
}
