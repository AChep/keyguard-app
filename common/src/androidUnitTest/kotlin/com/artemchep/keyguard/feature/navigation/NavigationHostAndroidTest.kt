package com.artemchep.keyguard.feature.navigation

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationHostAndroidTest {
    @Test
    fun `the callback is enabled in the same call that changes the host`() {
        var fallbackCalls = 0
        val dispatcher = OnBackPressedDispatcher { fallbackCalls++ }
        val host = NavigationHost()
        val remove = dispatcher.addNavigationHostCallback(host)
        assertFalse(dispatcher.hasEnabledCallbacks())

        var canPop = true
        var pops = 0
        host.registerBackTarget(canPop = { canPop }) {
            pops++
            // What a router does when it pops its last entry.
            canPop = false
            host.invalidateBack()
        }
        assertTrue(dispatcher.hasEnabledCallbacks())

        dispatcher.onBackPressed()
        assertEquals(1, pops)
        assertEquals(0, fallbackCalls)
        assertFalse(dispatcher.hasEnabledCallbacks())

        dispatcher.onBackPressed()
        assertEquals(1, pops)
        assertEquals(1, fallbackCalls)

        canPop = true
        host.invalidateBack()
        assertTrue(dispatcher.hasEnabledCallbacks())
        remove()
        assertFalse(dispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `a press that nothing can handle reaches the fallback`() {
        var fallbackCalls = 0
        val dispatcher = OnBackPressedDispatcher { fallbackCalls++ }
        val host = NavigationHost()
        var canPop = true
        var pops = 0
        host.registerBackTarget(canPop = { canPop }) { pops++ }
        dispatcher.addNavigationHostCallback(host)
        assertTrue(dispatcher.hasEnabledCallbacks())

        // Nothing invalidated the host since the target stopped being poppable.
        canPop = false
        dispatcher.onBackPressed()

        assertEquals(0, pops)
        assertEquals(1, fallbackCalls)
        assertFalse(dispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `start, progress and press reach the pinned target`() {
        var fallbackCalls = 0
        val dispatcher = OnBackPressedDispatcher { fallbackCalls++ }
        val host = NavigationHost()
        val gesture = RecordingGestureListener()
        var pops = 0
        host.registerBackTarget(canPop = { true }, gesture = gesture) { pops++ }
        dispatcher.addNavigationHostCallback(host)

        dispatcher.dispatchOnBackStarted(
            BackEventCompat(touchX = 10f, touchY = 20f, progress = 0f, swipeEdge = BackEventCompat.EDGE_LEFT),
        )
        // Registered after the start and deeper, so it would win a new pick.
        var otherPops = 0
        host.registerBackTarget(depth = { 1 }, canPop = { true }) { otherPops++ }
        dispatcher.dispatchOnBackProgressed(
            BackEventCompat(touchX = 30f, touchY = 40f, progress = 0.5f, swipeEdge = BackEventCompat.EDGE_LEFT),
        )
        dispatcher.onBackPressed()

        val expectedStart = NavigationBackEvent(
            progress = 0f,
            swipeEdge = NavigationBackEdge.LEFT,
            touchX = 10f,
            touchY = 20f,
        )
        val expectedProgress = NavigationBackEvent(
            progress = 0.5f,
            swipeEdge = NavigationBackEdge.LEFT,
            touchX = 30f,
            touchY = 40f,
        )
        assertEquals(listOf(expectedStart), gesture.started)
        assertEquals(listOf(expectedProgress), gesture.progressed)
        assertEquals(0, gesture.cancels)
        assertEquals(1, pops)
        assertEquals(0, otherPops)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun `cancel reaches the pinned target`() {
        var fallbackCalls = 0
        val dispatcher = OnBackPressedDispatcher { fallbackCalls++ }
        val host = NavigationHost()
        val gesture = RecordingGestureListener()
        var pops = 0
        host.registerBackTarget(canPop = { true }, gesture = gesture) { pops++ }
        dispatcher.addNavigationHostCallback(host)

        dispatcher.dispatchOnBackStarted(backEvent(BackEventCompat.EDGE_RIGHT))
        dispatcher.dispatchOnBackCancelled()

        assertEquals(1, gesture.started.size)
        assertEquals(1, gesture.cancels)
        assertEquals(0, pops)
        assertEquals(0, fallbackCalls)
        // The next press is a regular one.
        dispatcher.onBackPressed()
        assertEquals(1, pops)
        assertEquals(1, gesture.cancels)
    }

    @Test
    fun `each swipe edge maps to its navigation edge`() {
        val dispatcher = OnBackPressedDispatcher { }
        val host = NavigationHost()
        val gesture = RecordingGestureListener()
        host.registerBackTarget(canPop = { true }, gesture = gesture) { }
        dispatcher.addNavigationHostCallback(host)

        listOf(
            BackEventCompat.EDGE_LEFT,
            BackEventCompat.EDGE_RIGHT,
            BackEventCompat.EDGE_NONE,
        ).forEach { edge ->
            dispatcher.dispatchOnBackStarted(backEvent(edge))
            dispatcher.dispatchOnBackCancelled()
        }

        assertEquals(
            listOf(
                NavigationBackEdge.LEFT,
                NavigationBackEdge.RIGHT,
                NavigationBackEdge.NONE,
            ),
            gesture.started.map { it.swipeEdge },
        )
    }

    @Test
    fun `a target removed mid-gesture leaves the fallback uncalled`() {
        var fallbackCalls = 0
        val dispatcher = OnBackPressedDispatcher { fallbackCalls++ }
        val host = NavigationHost()
        val gesture = RecordingGestureListener()
        var pops = 0
        val unregister = host.registerBackTarget(canPop = { true }, gesture = gesture) { pops++ }
        dispatcher.addNavigationHostCallback(host)

        dispatcher.dispatchOnBackStarted(backEvent(BackEventCompat.EDGE_LEFT))
        unregister()
        assertEquals(1, gesture.cancels)
        // The callback consumes the rest of the gesture.
        assertTrue(dispatcher.hasEnabledCallbacks())
        dispatcher.dispatchOnBackProgressed(backEvent(BackEventCompat.EDGE_LEFT, progress = 0.5f))
        assertTrue(dispatcher.hasEnabledCallbacks())

        dispatcher.onBackPressed()
        assertEquals(0, pops)
        assertEquals(0, fallbackCalls)
        assertEquals(emptyList(), gesture.progressed)
        assertFalse(dispatcher.hasEnabledCallbacks())
    }

    private class RecordingGestureListener : NavigationBackGestureListener {
        val started = mutableListOf<NavigationBackEvent>()
        val progressed = mutableListOf<NavigationBackEvent>()
        var cancels = 0

        override fun onBackStarted(event: NavigationBackEvent) {
            started += event
        }

        override fun onBackProgressed(event: NavigationBackEvent) {
            progressed += event
        }

        override fun onBackCancelled() {
            cancels++
        }
    }

    private fun backEvent(
        swipeEdge: Int,
        progress: Float = 0f,
    ) = BackEventCompat(
        touchX = 0f,
        touchY = 0f,
        progress = progress,
        swipeEdge = swipeEdge,
    )
}
