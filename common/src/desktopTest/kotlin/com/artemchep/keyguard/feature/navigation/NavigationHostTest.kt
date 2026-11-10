package com.artemchep.keyguard.feature.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("TooManyFunctions")
class NavigationHostTest {
    //
    // Back targets
    //

    @Test
    fun `isBackEnabled follows the registered targets`() {
        val host = NavigationHost()
        val changes = mutableListOf<Boolean>()
        host.addBackEnabledListener { changes += it }
        assertFalse(host.isBackEnabled)

        host.registerBackTarget(canPop = { false }) {}
        assertFalse(host.isBackEnabled)
        val unregister = host.registerBackTarget(canPop = { true }) {}
        assertTrue(host.isBackEnabled)
        unregister()
        assertFalse(host.isBackEnabled)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `invalidateBack picks up a target that became poppable`() {
        val host = NavigationHost()
        val changes = mutableListOf<Boolean>()
        host.addBackEnabledListener { changes += it }
        var canPop = false
        host.registerBackTarget(canPop = { canPop }) {}

        canPop = true
        // Not evaluated again yet.
        assertFalse(host.isBackEnabled)
        host.invalidateBack()
        assertTrue(host.isBackEnabled)

        canPop = false
        host.invalidateBack()
        assertFalse(host.isBackEnabled)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `listeners hear only changes and nothing after removal`() {
        val host = NavigationHost()
        val changes = mutableListOf<Boolean>()
        val removeListener = host.addBackEnabledListener { changes += it }

        host.registerBackTarget(canPop = { true }) {}
        host.registerBackTarget(canPop = { true }) {}
        host.invalidateBack()
        assertEquals(listOf(true), changes)

        removeListener()
        val unregister = host.registerBackTarget(canPop = { false }) {}
        unregister()
        host.invalidateBack()
        assertEquals(listOf(true), changes)
    }

    @Test
    fun `a tie in depth goes to the latest registration`() {
        val host = NavigationHost()
        val calls = mutableListOf<String>()
        host.registerBackTarget(depth = { 1 }, canPop = { true }) { calls += "first" }
        val unregisterSecond = host.registerBackTarget(depth = { 1 }, canPop = { true }) { calls += "second" }
        host.registerBackTarget(depth = { 0 }, canPop = { true }) { calls += "shallow" }
        host.registerBackTarget(depth = { 2 }, canPop = { false }) { calls += "stuck" }

        assertTrue(host.handleBack())
        unregisterSecond()
        assertTrue(host.handleBack())
        assertEquals(listOf("second", "first"), calls)
    }

    @Test
    fun `startBack pins the deepest target that can pop`() {
        val host = NavigationHost()
        val calls = mutableListOf<String>()
        host.registerRecordingTarget("shallow", calls, depth = 1)
        host.registerRecordingTarget("deep", calls, depth = 2)
        host.registerRecordingTarget("stuck", calls, depth = 3, canPop = { false })

        host.startBack(backEvent(0f))
        host.progressBack(backEvent(0.5f))
        host.cancelBack()
        // Nothing is in flight anymore.
        host.progressBack(backEvent(0.75f))
        host.cancelBack()

        assertEquals(listOf("deep started 0.0", "deep progressed 0.5", "deep cancelled"), calls)
    }

    @Test
    fun `the release goes to the pinned target, not to a deeper later one`() {
        val host = NavigationHost()
        val calls = mutableListOf<String>()
        host.registerRecordingTarget("pinned", calls, depth = 1)

        host.startBack(backEvent(0f))
        host.registerRecordingTarget("late", calls, depth = 2)
        host.progressBack(backEvent(0.5f))
        assertTrue(host.handleBack())
        assertEquals(listOf("pinned started 0.0", "pinned progressed 0.5", "pinned back"), calls)

        // The gesture is over, so Back goes to the deepest target again.
        calls.clear()
        assertTrue(host.handleBack())
        assertEquals(listOf("late back"), calls)
    }

    @Test
    fun `removing the pinned target cancels the gesture and consumes the release`() {
        val host = NavigationHost()
        val calls = mutableListOf<String>()
        host.registerRecordingTarget("other", calls, depth = 1)
        val unregister = host.registerRecordingTarget("pinned", calls, depth = 2)

        host.startBack(backEvent(0f))
        unregister()
        unregister()
        host.progressBack(backEvent(0.5f))
        // The other target could pop, but it was not picked for the gesture.
        assertTrue(host.handleBack())
        assertEquals(listOf("pinned started 0.0", "pinned cancelled"), calls)

        calls.clear()
        assertTrue(host.handleBack())
        assertEquals(listOf("other back"), calls)
    }

    @Test
    fun `a pinned target that can no longer pop cancels at release`() {
        val host = NavigationHost()
        val calls = mutableListOf<String>()
        var canPop = true
        host.registerRecordingTarget("other", calls, depth = 1)
        host.registerRecordingTarget("pinned", calls, depth = 2, canPop = { canPop })

        host.startBack(backEvent(0f))
        canPop = false
        assertTrue(host.handleBack())

        assertEquals(listOf("pinned started 0.0", "pinned cancelled"), calls)
    }

    @Test
    fun `a gesture with no target to pop records nothing`() {
        val host = NavigationHost()
        val calls = mutableListOf<String>()
        host.registerRecordingTarget("stuck", calls, canPop = { false })

        host.startBack(backEvent(0f))
        host.progressBack(backEvent(0.5f))
        assertFalse(host.isBackEnabled)
        assertFalse(host.handleBack())

        // A target added during such a gesture gets the release
        // the regular way.
        host.startBack(backEvent(0f))
        host.registerRecordingTarget("late", calls)
        assertTrue(host.handleBack())
        assertEquals(listOf("late back"), calls)
    }

    @Test
    fun `isBackEnabled holds until the cancel of a gesture whose target left`() {
        val host = NavigationHost()
        val calls = mutableListOf<String>()
        val changes = mutableListOf<Boolean>()
        host.addBackEnabledListener { changes += it }
        val unregister = host.registerRecordingTarget("pinned", calls)

        host.startBack(backEvent(0f))
        unregister()
        host.invalidateBack()
        assertTrue(host.isBackEnabled)
        assertEquals(listOf(true), changes)

        host.cancelBack()
        assertFalse(host.isBackEnabled)
        assertEquals(listOf(true, false), changes)
        // Cancelled once, when it left.
        assertEquals(listOf("pinned started 0.0", "pinned cancelled"), calls)
    }

    @Test
    fun `isBackEnabled holds until the release of a target that stopped popping`() {
        val host = NavigationHost()
        val calls = mutableListOf<String>()
        val changes = mutableListOf<Boolean>()
        host.addBackEnabledListener { changes += it }
        var canPop = false
        host.registerRecordingTarget("pinned", calls, canPop = { canPop })

        // Not evaluated again yet: the start of the gesture does it.
        canPop = true
        host.startBack(backEvent(0f))
        assertTrue(host.isBackEnabled)

        canPop = false
        host.invalidateBack()
        assertTrue(host.isBackEnabled)
        assertEquals(listOf(true), changes)

        assertTrue(host.handleBack())
        assertFalse(host.isBackEnabled)
        assertEquals(listOf(true, false), changes)
        assertEquals(listOf("pinned started 0.0", "pinned cancelled"), calls)
    }

    @Test
    fun `Back without a gesture calls no gesture listener`() {
        val host = NavigationHost()
        val calls = mutableListOf<String>()
        host.registerRecordingTarget("target", calls)

        assertTrue(host.handleBack())
        host.cancelBack()

        assertEquals(listOf("target back"), calls)
    }

    @Test
    fun `a second startBack cancels the first`() {
        val host = NavigationHost()
        val calls = mutableListOf<String>()
        var deepCanPop = true
        host.registerRecordingTarget("shallow", calls, depth = 1)
        host.registerRecordingTarget("deep", calls, depth = 2, canPop = { deepCanPop })
        val changes = mutableListOf<Boolean>()
        host.addBackEnabledListener { changes += it }

        host.startBack(backEvent(0f))
        deepCanPop = false
        host.startBack(backEvent(0.25f))
        host.progressBack(backEvent(0.5f))
        assertTrue(host.handleBack())

        assertEquals(
            listOf(
                "deep started 0.0",
                "deep cancelled",
                "shallow started 0.25",
                "shallow progressed 0.5",
                "shallow back",
            ),
            calls,
        )
        // Back stays enabled across the restart.
        assertEquals(emptyList<Boolean>(), changes)
    }

    //
    // Routers
    //

    @Test
    fun `a queued navigation enables Back before the next frame`() = withNavigationHarness {
        start(routerHost("outer", screen("list")))
        assertFalse(host.isBackEnabled)
        val changes = mutableListOf<Boolean>()
        host.addBackEnabledListener { changes += it }

        router("outer").controller.queue(NavigationIntent.NavigateToRoute(screen("details")))
        assertTrue(host.isBackEnabled)
        router("outer").controller.queue(NavigationIntent.Pop)
        assertFalse(host.isBackEnabled)
        // No frame ran in between.
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `a Back interceptor on the top entry enables Back`() = withNavigationHarness {
        start(routerHost("outer", screen("list")))
        assertFalse(host.isBackEnabled)

        val unregister = router("outer").entries.single().interceptBackPress {}
        frame()
        assertTrue(host.isBackEnabled)

        unregister()
        frame()
        assertFalse(host.isBackEnabled)
    }

    @Test
    fun `a router that left the composition no longer counts`() = withNavigationHarness {
        start(routerHost("outer", screen("list")))
        router("outer").controller.queue(NavigationIntent.NavigateToRoute(screen("details")))
        assertTrue(host.isBackEnabled)

        mainRoute = null
        frame()

        assertFalse(host.isBackEnabled)
        assertFalse(host.handleBack())
    }

    @Test
    fun `saveRouters saves the pile of a router and of a nested router`() = withNavigationHarness {
        start(routerHost("outer", routerHost("nested", screen("nested list"))))
        val outerPile = router("outer").pile
        val nestedPile = router("nested").pile
        val store = NavigationStore()

        host.saveRouters(store)

        assertSame(outerPile, store.pop(outerPile.id))
        assertSame(nestedPile, store.pop(nestedPile.id))

        // Another window has none of these routers.
        val otherStore = NavigationStore()
        NavigationHost().saveRouters(otherStore)
        assertNull(otherStore.pop(outerPile.id))
        assertNull(otherStore.pop(nestedPile.id))
    }

    @Test
    fun `a Back gesture on a router publishes its preview to the content`() = withNavigationHarness {
        start(routerHost("outer", screen("list")))
        router("outer").controller.queue(NavigationIntent.NavigateToRoute(screen("details")))
        frame()
        val list = router("outer").entries.first()

        host.startBack(backEvent(0f))
        frame()

        val gesture = assertNotNull(router("outer").pile.backGesture)
        assertSame(router("outer").pile.value.last(), gesture.from)
        assertSame(list, gesture.to.value.single())
        assertSame(gesture, router("outer").back)

        host.cancelBack()
        frame()

        assertNull(router("outer").pile.backGesture)
        assertNull(router("outer").back)
        assertEquals(2, router("outer").entries.size)
    }

    @Test
    fun `a nested router does not see the Back gesture of its parent`() = withNavigationHarness {
        start(routerHost("outer", screen("list")))
        val intent = NavigationIntent.NavigateToStack("nested", routerHost("nested", screen("nested list")))
        router("outer").controller.queue(intent)
        frame()

        // The nested router is at its root, so the outer one gets the gesture.
        host.startBack(backEvent(0f))
        frame()

        assertNotNull(router("outer").back)
        assertNull(router("nested").pile.backGesture)
        assertNull(router("nested").back)
        host.cancelBack()
    }

    private fun backEvent(progress: Float) = NavigationBackEvent(
        progress = progress,
        swipeEdge = NavigationBackEdge.LEFT,
        touchX = 0f,
        touchY = 0f,
    )

    /** Adds a target that records its Back and gesture calls into [calls]. */
    private fun NavigationHost.registerRecordingTarget(
        name: String,
        calls: MutableList<String>,
        depth: Int = 0,
        canPop: () -> Boolean = { true },
    ): () -> Unit = registerBackTarget(
        depth = { depth },
        canPop = canPop,
        gesture = object : NavigationBackGestureListener {
            override fun onBackStarted(event: NavigationBackEvent) {
                calls += "$name started ${event.progress}"
            }

            override fun onBackProgressed(event: NavigationBackEvent) {
                calls += "$name progressed ${event.progress}"
            }

            override fun onBackCancelled() {
                calls += "$name cancelled"
            }
        },
    ) { calls += "$name back" }
}
