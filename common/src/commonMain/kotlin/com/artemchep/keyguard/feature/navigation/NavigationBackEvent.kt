package com.artemchep.keyguard.feature.navigation

/** The screen edge a Back gesture starts from. */
enum class NavigationBackEdge {
    LEFT,
    RIGHT,

    /** Not an edge swipe, for example a Back button. */
    NONE,
}

/** One sample of a Back gesture. [progress] is in 0..1. */
data class NavigationBackEvent(
    val progress: Float,
    val swipeEdge: NavigationBackEdge,
    val touchX: Float,
    val touchY: Float,
)

/**
 * Follows the Back gesture a target was picked for. A release that
 * pops calls the Back of the target instead.
 */
interface NavigationBackGestureListener {
    fun onBackStarted(event: NavigationBackEvent)

    fun onBackProgressed(event: NavigationBackEvent)

    /** The gesture ends without calling the Back of the target. */
    fun onBackCancelled()
}
