package com.artemchep.keyguard.feature.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
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
import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.platform.LocalAnimationFactor
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
 * @param scalePeek whether the seek is a predictive stack pop, whose
 *   [motion] moves both screens, see [NavigationPeekBox]
 */
internal class NavigationSeek<S>(
    val gesture: NavigationBackGesture,
    val targetState: S,
    val backState: S,
    val scalePeek: Boolean,
) {
    var stopped = false
    val motion = if (scalePeek) NavigationBackMotion(gesture) else null
}

/** Plain holder, only the effect of the wrapper reads and writes it. */
private class NavigationSeekMemory<S> {
    /** The last seek that has not been settled yet. */
    var last: NavigationSeek<S>? = null
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
 * is a predictive stack pop also moves both screens, see
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
    // Read once for the seek, transform and decoration: all use the same scale.
    val animationScaleState = rememberUpdatedState(LocalAnimationFactor)
    LaunchedEffect(transitionState, seek, targetState) {
        transitionState.settle(
            memory = memory,
            targetState = targetState,
            seek = seek,
            totalDurationNanos = { transition.totalDurationNanos },
            animationScale = animationScaleState.value,
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
    animationScale: Float,
) {
    val last = memory.last
    if (last != null) {
        val keep = if (seek != null) {
            // The next gesture previews the same state.
            last.backState == seek.backState && currentState == targetState
        } else {
            // Released: the previewed state is the live one now.
            last.backState == targetState
        }
        if (!keep) {
            undoSeek(last.backState, totalDurationNanos())
            memory.last = null
        }
    }

    if (seek == null) {
        memory.last = null
        val motion = last?.takeIf { it.backState == targetState }?.motion
        if (motion != null && this.targetState == targetState && currentState != targetState) {
            // Rebase the release at the actual drag pose. Playing the rest of the
            // drag segment first would add a shrink after every short swipe.
            motion.commit(fraction)
            animateTo(targetState, NavigationAnimation.backGestureCommitSpec(animationScale))
        } else {
            animateTo(targetState)
        }
        return
    }

    if (memory.last == null && this.targetState != targetState) {
        // The live content is not composed yet.
        animateTo(targetState)
    }
    memory.last = seek
    snapshotFlow { seek.gesture.progress }
        .collectLatest { progress ->
            val fraction = if (seek.scalePeek) {
                NavigationAnimation.backGestureFraction(progress)
            } else {
                NavigationAnimation.backGestureCrossfadeFraction(progress)
            }
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
