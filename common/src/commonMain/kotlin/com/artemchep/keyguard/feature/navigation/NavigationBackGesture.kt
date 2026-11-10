package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * A Back gesture that a router previews: the stack it started on
 * and the stack Back leads to.
 */
@Stable
class NavigationBackGesture internal constructor(
    /** The active stack of the router when the gesture started. */
    val from: NavigationStack,
    /** The stack the router makes active if the gesture pops. */
    val to: NavigationStack,
    val swipeEdge: NavigationBackEdge,
    progress: Float,
) {
    /** The progress of the gesture, in 0..1. Snapshot state. */
    var progress: Float by mutableFloatStateOf(progress)
        internal set

    override fun toString(): String {
        return "NavigationBackGesture(from=${from.id}, to=${to.id}, swipeEdge=$swipeEdge, progress=$progress)"
    }
}

/** The Back gesture the nearest router previews, or `null`. */
val LocalNavigationBackGesture = compositionLocalOf<NavigationBackGesture?> { null }
