package com.artemchep.keyguard.feature.twopane

import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.feature.navigation.NavigationBackEdge
import com.artemchep.keyguard.feature.navigation.NavigationBackEvent
import com.artemchep.keyguard.feature.navigation.NavigationComposeHarness
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.twoPaneHost
import com.artemchep.keyguard.feature.navigation.withNavigationHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How [TwoPaneNavigationContent] renders a Back gesture: closing the
 * detail pane moves the panes, a pop inside the detail stack moves only
 * the detail pane.
 */
class TwoPaneBackGestureTest {
    @Test
    fun `phone - closing the detail previews the list and the release keeps that composition`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            startPhone()
            push("detail")
            assertFalse("list" in screens.composed)
            val compositions = screens.compositions("list")

            host.startBack(backEvent(0f))
            frame()
            assertNotNull(router(PANES).back)
            assertTrue(screens.isFinishing("list"))
            assertTrue(screens.isLive("detail"))

            host.progressBack(backEvent(0.5f))
            frame()
            assertTrue(screens.isFinishing("list"))
            assertTrue(screens.isLive("detail"))
            assertEquals(compositions + 1, screens.compositions("list"))

            assertTrue(host.handleBack())
            render()
            // Live in the first frame after the pop.
            assertTrue(screens.isLive("list"))
            assertTrue(screens.isFinishing("detail"))

            frame()
            assertTrue(screens.isLive("list"))
            assertFalse("detail" in screens.composed)
            // The pane swap kept the composition of the preview.
            assertEquals(compositions + 1, screens.compositions("list"))
            assertEquals(1, router(PANES).entries.size)
        }

    @Test
    fun `phone - cancel removes the previewed list and keeps the detail`() =
        withNavigationHarness(NavAnimation.CROSSFADE) {
            startPhone()
            push("detail")
            host.startBack(backEvent(0f))
            frame()
            host.progressBack(backEvent(0.8f))
            frame()
            assertTrue(screens.isFinishing("list"))

            host.cancelBack()
            frame()

            assertFalse("list" in screens.composed)
            assertTrue(screens.isLive("detail"))
            assertEquals(1, screens.compositions("detail"))
            assertEquals(2, router(PANES).entries.size)
        }

    @Test
    fun `phone - a pop inside the detail stack previews only the previous detail`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            startPhone()
            push("d1")
            push("d2")
            assertFalse("d1" in screens.composed)
            val compositions = screens.compositions("d1")

            host.startBack(backEvent(0f))
            frame()
            host.progressBack(backEvent(0.5f))
            frame()
            assertTrue(screens.isFinishing("d1"))
            assertTrue(screens.isLive("d2"))
            assertFalse("list" in screens.composed)

            assertTrue(host.handleBack())
            frame()
            assertTrue(screens.isLive("d1"))
            assertFalse("d2" in screens.composed)
            assertFalse("list" in screens.composed)
            assertEquals(compositions + 1, screens.compositions("d1"))
            assertEquals(2, router(PANES).entries.size)
        }

    @Test
    fun `tablet - closing the detail previews the placeholder and the list pane stays`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            startTablet()
            assertTrue(hasText(PLACEHOLDER_TEXT))
            push("detail")
            assertTrue(screens.isLive("list"))
            assertTrue(screens.isLive("detail"))
            assertFalse(hasText(PLACEHOLDER_TEXT))
            assertEquals(1, screens.compositions("list"))

            host.startBack(backEvent(0f))
            frame()
            host.progressBack(backEvent(0.5f))
            frame()
            assertNotNull(router(PANES).back)
            assertTrue(hasText(PLACEHOLDER_TEXT))
            assertTrue(screens.isLive("detail"))
            assertTrue(screens.isLive("list"))
            assertEquals(1, screens.compositions("list"))

            assertTrue(host.handleBack())
            render()
            assertTrue(screens.isLive("list"))
            assertTrue(screens.isFinishing("detail"))

            frame()
            assertTrue(screens.isLive("list"))
            assertFalse("detail" in screens.composed)
            assertTrue(hasText(PLACEHOLDER_TEXT))
            assertEquals(1, screens.compositions("list"))
            assertEquals(1, router(PANES).entries.size)
        }

    @Test
    fun `tablet - a pop inside the detail stack previews only the previous detail`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            startTablet()
            push("d1")
            push("d2")
            assertFalse("d1" in screens.composed)
            val compositions = screens.compositions("d1")

            host.startBack(backEvent(0f))
            frame()
            host.progressBack(backEvent(0.5f))
            frame()
            assertTrue(screens.isFinishing("d1"))
            assertTrue(screens.isLive("d2"))
            assertTrue(screens.isLive("list"))
            assertFalse(hasText(PLACEHOLDER_TEXT))

            assertTrue(host.handleBack())
            frame()
            assertTrue(screens.isLive("d1"))
            assertFalse("d2" in screens.composed)
            assertTrue(screens.isLive("list"))
            assertEquals(compositions + 1, screens.compositions("d1"))
            assertEquals(1, screens.compositions("list"))
            assertEquals(2, router(PANES).entries.size)
        }

    @Test
    fun `phone - push and pop without a gesture show the top screen`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            startPhone()
            assertTrue(screens.isLive("list"))

            push("d1")
            assertTrue(screens.isLive("d1"))
            assertFalse("list" in screens.composed)

            push("d2")
            assertTrue(screens.isLive("d2"))
            assertFalse("d1" in screens.composed)

            assertTrue(host.handleBack())
            frame()
            assertTrue(screens.isLive("d1"))
            assertFalse("d2" in screens.composed)

            assertTrue(host.handleBack())
            frame()
            assertTrue(screens.isLive("list"))
            assertFalse("d1" in screens.composed)
            assertEquals(2, screens.compositions("list"))
            assertEquals(1, router(PANES).entries.size)
        }

    @Test
    fun `tablet - push and pop without a gesture keep the list pane`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            startTablet()
            push("d1")
            assertTrue(screens.isLive("d1"))
            assertTrue(screens.isLive("list"))

            assertTrue(host.handleBack())
            frame()
            assertFalse("d1" in screens.composed)
            assertTrue(screens.isLive("list"))
            assertTrue(hasText(PLACEHOLDER_TEXT))
            assertEquals(1, screens.compositions("list"))
        }

    private fun NavigationComposeHarness.startPhone() {
        start(
            route = twoPaneHost(PANES, screen("list")),
            width = NavigationComposeHarness.NARROW_WIDTH,
            height = NavigationComposeHarness.NARROW_HEIGHT,
        )
    }

    private fun NavigationComposeHarness.startTablet() {
        start(
            route = twoPaneHost(PANES, screen("list")),
            width = NavigationComposeHarness.WIDE_WIDTH,
            height = NavigationComposeHarness.WIDE_HEIGHT,
        )
    }

    private fun NavigationComposeHarness.push(name: String) {
        router(PANES).controller.queue(NavigationIntent.NavigateToRoute(screen(name)))
        frame()
    }

    private fun backEvent(progress: Float) = NavigationBackEvent(
        progress = progress,
        swipeEdge = NavigationBackEdge.LEFT,
        touchX = 0f,
        touchY = 0f,
    )

    private companion object {
        const val PANES = "panes"

        /** The text of [EmptyKeyguardBox], the placeholder of the detail pane. */
        const val PLACEHOLDER_TEXT = "Keyguard"
    }
}
