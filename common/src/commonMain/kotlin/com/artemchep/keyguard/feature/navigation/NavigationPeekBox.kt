package com.artemchep.keyguard.feature.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FiniteAnimationSpec
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
    /** The last peek whose leaving screen the content was. */
    var peek: NavigationSeek<S>? = null
}

/**
 * Hosts the [content] of the [state], and decorates it while the [peek]
 * plays: the leaving screen gets rounded corners, a shadow and a margin
 * from the far edge and fades out, the screen under it a scrim.
 *
 * The leaving screen keeps its decoration while it exits, even after a
 * newer seek or target ends the [peek]: the decoration is the only fade
 * of the screen, see [NavigationAnimation.transformBackGesture].
 */
@Composable
internal fun <S> AnimatedVisibilityScope.NavigationPeekBox(
    state: S,
    peek: NavigationSeek<S>?,
    scale: Float,
    content: @Composable () -> Unit,
) {
    val memory = remember {
        NavigationPeekTopMemory<S>()
    }
    val topPeek = if (peek != null && state == peek.targetState) {
        peek
    } else {
        memory.peek
            ?.takeIf { transition.targetState == EnterExitState.PostExit }
    }
    SideEffect {
        memory.peek = topPeek
    }
    val isTop = topPeek != null
    val isUnder = peek != null && state == peek.backState
    val topModifier = navigationPeekTop(
        enabled = isTop,
        scale = scale,
        towardRight = topPeek?.gesture?.swipeEdge != NavigationBackEdge.RIGHT,
    )
    val scrimAlpha = if (MaterialTheme.colorScheme.isDark) {
        BACK_GESTURE_SCRIM_ALPHA_DARK
    } else {
        BACK_GESTURE_SCRIM_ALPHA_LIGHT
    }
    val scrim = animateNavigationPeek(
        enabled = isUnder,
        peekState = EnterExitState.PreEnter,
        peekValue = scrimAlpha,
        idleValue = 0f,
        animationSpec = NavigationAnimation.backGestureScrimSpec(scale, scrimAlpha),
        label = "NavigationPeekScrim",
    )
    val underModifier = if (isUnder) {
        Modifier.navigationPeekScrim(scrim, MaterialTheme.colorScheme.scrim)
    } else {
        Modifier
    }
    Box(
        modifier = topModifier.then(underModifier),
        propagateMinConstraints = true,
    ) {
        content()
    }
}

@Composable
private fun AnimatedVisibilityScope.navigationPeekTop(
    enabled: Boolean,
    scale: Float,
    towardRight: Boolean,
): Modifier {
    val progress = animateNavigationPeek(
        enabled = enabled,
        peekState = EnterExitState.PostExit,
        peekValue = 1f,
        idleValue = 0f,
        animationSpec = NavigationAnimation.backGesturePeekSpec(scale),
        label = "NavigationPeek",
    )
    val shadow = animateNavigationPeek(
        enabled = enabled,
        peekState = EnterExitState.PostExit,
        peekValue = BACK_GESTURE_SHADOW_EXIT_FACTOR,
        idleValue = 0f,
        animationSpec = NavigationAnimation.backGestureShadowSpec(scale),
        label = "NavigationPeekShadow",
    )
    val alpha = animateNavigationPeek(
        enabled = enabled,
        peekState = EnterExitState.PostExit,
        peekValue = 0f,
        idleValue = 1f,
        animationSpec = NavigationAnimation.backGestureFadeSpec(scale),
        label = "NavigationPeekAlpha",
    )
    return if (enabled) {
        // Same rule as the transform: a swipe from the right
        // edge scales the screen toward the left edge.
        Modifier.navigationPeekTop(progress, shadow, alpha, towardRight)
    } else {
        Modifier
    }
}

/**
 * An animated value of the peek decoration, at the [peekValue] in the
 * [peekState] while [enabled] and at the [idleValue] otherwise.
 *
 * It lives as long as the content, like the animations of AnimatedContent:
 * a new animation would take its initial value from the current state of
 * the transition, which is stale after a cancelled seek. A disabled
 * animation snaps, so it does not hold the transitions it is not a part of.
 */
@Composable
private fun AnimatedVisibilityScope.animateNavigationPeek(
    enabled: Boolean,
    peekState: EnterExitState,
    peekValue: Float,
    idleValue: Float,
    animationSpec: FiniteAnimationSpec<Float>,
    label: String,
): State<Float> = transition.animateFloat(
    transitionSpec = {
        if (enabled) animationSpec else snap()
    },
    label = label,
) { enterExit ->
    if (enabled && enterExit == peekState) peekValue else idleValue
}

private fun Modifier.navigationPeekTop(
    progress: State<Float>,
    shadow: State<Float>,
    alpha: State<Float>,
    towardRight: Boolean,
): Modifier = graphicsLayer {
    val value = progress.value
    shape = RoundedCornerShape(BackGesturePeekCorner.toPx() * value)
    clip = true
    shadowElevation = BackGesturePeekElevation.toPx() * shadow.value
    // The parent draws the shadow, so it is not clipped
    // by the alpha and fades with the screen.
    this.alpha = alpha.value
    // Away from the far edge, to show the rounded corners there.
    val margin = BackGesturePeekMargin.toPx() * value
    translationX = if (towardRight) -margin else margin
}

private fun Modifier.navigationPeekScrim(
    alpha: State<Float>,
    color: Color,
): Modifier = drawWithContent {
    drawContent()
    drawRect(color = color, alpha = alpha.value)
}
