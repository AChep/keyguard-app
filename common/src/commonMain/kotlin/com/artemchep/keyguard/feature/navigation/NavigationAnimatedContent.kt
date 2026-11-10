package com.artemchep.keyguard.feature.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.platform.LocalAnimationFactor
import com.artemchep.keyguard.ui.theme.isDark
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * The seek of one Back gesture: it seeks from the [targetState] to the
 * [backState] the gesture started with, and stops for good once either
 * of them changes.
 *
 * @param scalePeek whether the seek scales the leaving screen down, see
 *   [NavigationAnimation.transformBackGesture]
 */
private class NavigationSeek<S>(
    val gesture: NavigationBackGesture,
    val targetState: S,
    val backState: S,
    val scalePeek: Boolean,
) {
    var stopped = false
}

/** The target of the last seek that has not been settled yet. */
private class NavigationSeekTarget<S>(
    val state: S,
)

/** Plain holder, only the effect of the wrapper reads and writes it. */
private class NavigationSeekMemory<S> {
    var last: NavigationSeekTarget<S>? = null
}

/**
 * Animates the changes of the [targetState] like [AnimatedContent], and
 * follows the Back gesture that previews the [backState]: the transition
 * seeks to the [backState] with the progress of the gesture. A release
 * that makes the [backState] the target continues the animation, a cancel
 * plays it back.
 *
 * The content of every state other than the [targetState] is
 * finishing, see [LocalNavigationNodeFinishing]. A Back gesture that
 * scales the leaving screen down also decorates both screens, see
 * [NavigationPeekBox].
 *
 * @param back the Back gesture that previews the [backState], or `null`
 * @param backState the state Back leads to, the [targetState] if there
 *   is nothing to preview
 * @param zIndex the z-index of a state, read when the state enters
 */
@Composable
internal fun <S> NavigationAnimatedContent(
    targetState: S,
    back: NavigationBackGesture?,
    backState: S,
    transitionType: (initialState: S, targetState: S) -> NavigationAnimationType,
    zIndex: (S) -> Float,
    modifier: Modifier = Modifier,
    content: @Composable (S) -> Unit,
) {
    val getNavAnimation = koinInject<GetNavAnimation>()
    val seek = rememberNavigationSeek(
        back = back,
        targetState = targetState,
        backState = backState,
        animation = getNavAnimation().value,
        transitionType = transitionType,
    )

    val transitionState = remember {
        SeekableTransitionState(targetState)
    }
    val transition = rememberTransition(transitionState, label = "NavigationAnimatedContent")
    val memory = remember {
        NavigationSeekMemory<S>()
    }
    LaunchedEffect(transitionState, seek, targetState) {
        transitionState.settle(
            memory = memory,
            targetState = targetState,
            seek = seek,
            totalDurationNanos = { transition.totalDurationNanos },
        )
    }

    NavigationLagBackTarget(
        lagging = targetState != transitionState.currentState &&
            targetState != transitionState.targetState,
    )
    val peek = rememberNavigationPeek(seek, transitionState)

    // The transition keeps the lambdas below, so they read the
    // latest values through these states.
    val liveState = rememberUpdatedState(targetState)
    val contentState = rememberUpdatedState(content)
    // Read once for the transform and the decoration: the two
    // must run on the same timeline.
    val animationScaleState = rememberUpdatedState(LocalAnimationFactor)
    val transitionSpec = rememberNavigationTransitionSpec(
        seek = seek,
        animationScaleState = animationScaleState,
        getNavAnimation = getNavAnimation,
        transitionType = transitionType,
        zIndex = zIndex,
    )
    transition.AnimatedContent(
        modifier = modifier,
        transitionSpec = transitionSpec,
    ) { state ->
        val finishing = LocalNavigationNodeFinishing.current ||
            state != liveState.value
        CompositionLocalProvider(
            LocalNavigationNodeFinishing provides finishing,
        ) {
            NavigationPeekBox(
                state = state,
                peek = peek.value,
                scale = animationScaleState.value,
            ) {
                contentState.value(state)
            }
        }
    }
}

/**
 * The seek of the [back] gesture, or `null` if it does not seek. One
 * seek target per gesture: the seek stops for good once the
 * [targetState] or the [backState] changes.
 */
@Composable
private fun <S> rememberNavigationSeek(
    back: NavigationBackGesture?,
    targetState: S,
    backState: S,
    animation: NavAnimation,
    transitionType: (initialState: S, targetState: S) -> NavigationAnimationType,
): NavigationSeek<S>? {
    val canSeek = animation != NavAnimation.DISABLED &&
        LocalAnimationFactor > 0f
    val pinned = remember(back) {
        back
            ?.takeIf { canSeek && backState != targetState }
            ?.let { gesture ->
                // Same rule as the transition spec of the segment.
                val type = navigationBackTransitionType(gesture) {
                    transitionType(targetState, backState)
                }
                NavigationSeek(
                    gesture = gesture,
                    targetState = targetState,
                    backState = backState,
                    scalePeek = animation == NavAnimation.DYNAMIC &&
                        type == NavigationAnimationType.GO_BACKWARD,
                )
            }
    }
    val seek = pinned?.takeIf { pin ->
        !pin.stopped && canSeek &&
            pin.targetState == targetState &&
            pin.backState == backState
    }
    SideEffect {
        if (pinned != null && seek == null) {
            pinned.stopped = true
        }
    }
    return seek
}

/**
 * The last seek that is a scale peek, while the transition plays its
 * segment: during the drag, after the release and while a cancel plays
 * back. `null` once the segment is over or another seek starts.
 */
@Composable
private fun <S> rememberNavigationPeek(
    seek: NavigationSeek<S>?,
    transitionState: SeekableTransitionState<S>,
): State<NavigationSeek<S>?> {
    val peekState = remember {
        mutableStateOf<NavigationSeek<S>?>(null)
    }
    // Only this function writes the state, so it does
    // not observe it.
    val last = Snapshot.withoutReadObservation { peekState.value }
    val next = when {
        seek != null -> seek.takeIf { it.scalePeek }
        last == null -> null
        // The cancel or the release has finished, or the
        // transition moved on to another segment.
        transitionState.targetState != last.backState ||
            transitionState.currentState != last.targetState -> null
        else -> last
    }
    if (next !== last) {
        peekState.value = next
    }
    return peekState
}

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
private fun <S> AnimatedVisibilityScope.NavigationPeekBox(
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
    val progress = animateNavigationPeek(
        enabled = isTop,
        peekState = EnterExitState.PostExit,
        peekValue = 1f,
        idleValue = 0f,
        animationSpec = NavigationAnimation.backGesturePeekSpec(scale),
        label = "NavigationPeek",
    )
    val shadow = animateNavigationPeek(
        enabled = isTop,
        peekState = EnterExitState.PostExit,
        peekValue = BACK_GESTURE_SHADOW_EXIT_FACTOR,
        idleValue = 0f,
        animationSpec = NavigationAnimation.backGestureShadowSpec(scale),
        label = "NavigationPeekShadow",
    )
    val alpha = animateNavigationPeek(
        enabled = isTop,
        peekState = EnterExitState.PostExit,
        peekValue = 0f,
        idleValue = 1f,
        animationSpec = NavigationAnimation.backGestureFadeSpec(scale),
        label = "NavigationPeekAlpha",
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
    val topModifier = if (topPeek != null) {
        // Same rule as the transform: a swipe from the right
        // edge scales the screen toward the left edge.
        val towardRight = topPeek.gesture.swipeEdge != NavigationBackEdge.RIGHT
        Modifier.navigationPeekTop(
            progress = progress,
            shadow = shadow,
            alpha = alpha,
            towardRight = towardRight,
        )
    } else {
        Modifier
    }
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

/**
 * The transition picks up a new target state one frame later. In that
 * frame the old content is already finishing and the new one is not
 * composed yet, so neither holds a Back target. While [lagging], this
 * target consumes a Back that nothing else could take.
 */
@Composable
private fun NavigationLagBackTarget(
    lagging: Boolean,
) {
    val host = LocalNavigationHost.current
    if (!lagging) {
        return
    }
    DisposableEffect(host) {
        val unregister = host.registerBackTarget(
            depth = { Int.MIN_VALUE },
            canPop = { true },
            onBack = {
                // Do nothing.
            },
        )
        onDispose {
            unregister()
        }
    }
}

@Composable
private fun <S> rememberNavigationTransitionSpec(
    seek: NavigationSeek<S>?,
    animationScaleState: State<Float>,
    getNavAnimation: GetNavAnimation,
    transitionType: (initialState: S, targetState: S) -> NavigationAnimationType,
    zIndex: (S) -> Float,
): AnimatedContentTransitionScope<S>.() -> ContentTransform {
    val seekState = rememberUpdatedState(seek)
    val transitionTypeState = rememberUpdatedState(transitionType)
    val zIndexState = rememberUpdatedState(zIndex)
    return remember(getNavAnimation) {
        {
            // Only the segment that a gesture seeks to its back
            // state follows the gesture.
            val gesture = seekState.value
                ?.takeIf { it.backState == targetState }
                ?.gesture
            val type = navigationBackTransitionType(gesture) {
                transitionTypeState.value(initialState, targetState)
            }
            val targetZIndex = zIndexState.value(targetState)
            NavigationAnimation
                .transform(
                    scale = animationScaleState.value,
                    animationType = getNavAnimation().value,
                    transitionType = type,
                    backEdge = gesture?.swipeEdge,
                )
                .apply {
                    targetContentZIndex = targetZIndex
                }
        }
    }
}

/**
 * The type of a transition that the [gesture] seeks, or the [regular]
 * type if no gesture seeks it. A tab back from one stack to another
 * switches the content.
 */
internal inline fun navigationBackTransitionType(
    gesture: NavigationBackGesture?,
    regular: () -> NavigationAnimationType,
): NavigationAnimationType = if (gesture != null && gesture.from.id != gesture.to.id) {
    NavigationAnimationType.SWITCH
} else {
    regular()
}

/**
 * Moves the transition to the [targetState], or seeks it with the [seek]
 * if there is one. A seek whose target is no longer wanted is played
 * back first: seeking or animating toward another state would leave the
 * live content neither current nor target.
 */
private suspend fun <S> SeekableTransitionState<S>.settle(
    memory: NavigationSeekMemory<S>,
    targetState: S,
    seek: NavigationSeek<S>?,
    totalDurationNanos: () -> Long,
) {
    val last = memory.last
    if (last != null) {
        val keep = if (seek != null) {
            // The next gesture previews the same state.
            last.state == seek.backState && currentState == targetState
        } else {
            // Released: the previewed state is the live one now.
            last.state == targetState
        }
        if (!keep) {
            undoSeek(last.state, totalDurationNanos())
            memory.last = null
        }
    }

    if (seek == null) {
        memory.last = null
        animateTo(targetState)
        return
    }

    if (memory.last == null && this.targetState != targetState) {
        // The live content is not composed yet.
        animateTo(targetState)
    }
    memory.last = NavigationSeekTarget(seek.backState)
    snapshotFlow { seek.gesture.progress }
        .collectLatest { progress ->
            val fraction = NavigationAnimation.backGestureFraction(progress)
            seekTo(fraction, seek.backState)
        }
}

/**
 * Plays a seek to the [target] back to the start and snaps to the
 * current state, the way the cancel of a predictive back does in
 * androidx navigation.
 */
private suspend fun <S> SeekableTransitionState<S>.undoSeek(
    target: S,
    totalDurationNanos: Long,
) {
    // The seek never moved the transition.
    if (targetState != target || currentState == target) {
        return
    }
    val start = fraction
    if (start > 0f) {
        val durationMillis = (start * totalDurationNanos / NANOS_PER_MILLI).toInt()
        coroutineScope {
            val fractions = Channel<Float>(Channel.CONFLATED)
            launch {
                animate(
                    initialValue = start,
                    targetValue = 0f,
                    animationSpec = tween(durationMillis),
                ) { value, _ ->
                    fractions.trySend(value)
                }
                fractions.close()
            }
            for (value in fractions) {
                seekTo(value.coerceIn(0f, 1f))
            }
        }
    }
    snapTo(currentState)
}
