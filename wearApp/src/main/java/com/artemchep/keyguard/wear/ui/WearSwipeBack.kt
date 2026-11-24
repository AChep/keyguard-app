package com.artemchep.keyguard.wear.ui

import android.os.Build
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.navigation.LocalNavigationHost
import com.artemchep.keyguard.feature.navigation.NavigationBackEdge
import com.artemchep.keyguard.feature.navigation.NavigationBackEvent
import com.artemchep.keyguard.feature.navigation.NavigationHost

/** A release past this share of the width pops. */
private const val SWIPE_BACK_COMMIT_PROGRESS = 0.35f

/** A left-to-right fling faster than this pops, however short. */
private val SwipeBackFlingVelocity = 400.dp

/**
 * Turns a left-to-right swipe into a Back gesture of the nearest
 * [NavigationHost], so the router previews the previous screen the way
 * it does for predictive back.
 *
 * Wear OS sends predictive back events from API 36. Below that the
 * window swipe-to-dismiss sends no progress, and some watches, such as
 * ColorOS Watch, have no swipe at all. This is the fallback, like the
 * `BasicSwipeToDismissBox` that `SwipeDismissableNavHost` uses there.
 *
 * The swipe is on only while the host can pop, so on the root screen
 * the swipe stays with the system. From API 36 the [content] is composed
 * as is, without the swipe.
 */
@Composable
fun WearSwipeBackBox(
    content: @Composable () -> Unit,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        content()
        return
    }
    WearSwipeBackBoxImpl(content)
}

@Composable
private fun WearSwipeBackBoxImpl(
    content: @Composable () -> Unit,
) {
    val host = LocalNavigationHost.current
    var canPop by remember(host) { mutableStateOf(host.isBackEnabled) }
    val swipe = remember(host) { WearSwipeBack(host) }
    DisposableEffect(host) {
        canPop = host.isBackEnabled
        val remove = host.addBackEnabledListener { enabled ->
            canPop = enabled
            if (!enabled) swipe.cancel()
        }
        onDispose {
            remove()
            swipe.cancel()
        }
    }

    val flingVelocity = with(LocalDensity.current) { SwipeBackFlingVelocity.toPx() }
    val dragState = rememberDraggableState { delta ->
        swipe.drag(delta)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                swipe.width = size.width.toFloat()
            }
            // The window swipe-to-dismiss of Wear OS does not take a
            // swipe that a child view can scroll. Report that the content
            // scrolls to the start while the swipe is ours.
            .semantics {
                horizontalScrollAxisRange = ScrollAxisRange(
                    value = { if (canPop) 1f else 0f },
                    maxValue = { 1f },
                )
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = canPop,
                onDragStarted = { offset ->
                    swipe.start(offset)
                },
                onDragStopped = { velocity ->
                    swipe.stop(
                        velocity = velocity,
                        flingVelocity = flingVelocity,
                    )
                },
            ),
    ) {
        content()
    }
}

/**
 * Feeds one swipe at a time to the [host] as a Back gesture from
 * the left edge. Use it on the main thread only.
 */
private class WearSwipeBack(
    private val host: NavigationHost,
) {
    var width: Float = 0f

    private var inFlight = false

    private var start = Offset.Zero

    private var distance = 0f

    fun start(offset: Offset) {
        inFlight = true
        start = offset
        distance = 0f
        host.startBack(event())
    }

    fun drag(delta: Float) {
        if (!inFlight) return
        // A swipe to the left previews nothing.
        distance = (distance + delta).coerceAtLeast(0f)
        host.progressBack(event())
    }

    fun stop(velocity: Float, flingVelocity: Float) {
        if (!inFlight) return
        inFlight = false
        val commit = velocity > flingVelocity ||
            velocity >= 0f && progress() >= SWIPE_BACK_COMMIT_PROGRESS
        if (commit) {
            host.handleBack()
        } else {
            host.cancelBack()
        }
    }

    fun cancel() {
        if (!inFlight) return
        inFlight = false
        host.cancelBack()
    }

    private fun progress(): Float =
        if (width > 0f) (distance / width).coerceAtMost(1f) else 0f

    private fun event() = NavigationBackEvent(
        progress = progress(),
        swipeEdge = NavigationBackEdge.LEFT,
        touchX = start.x + distance,
        touchY = start.y,
    )
}
