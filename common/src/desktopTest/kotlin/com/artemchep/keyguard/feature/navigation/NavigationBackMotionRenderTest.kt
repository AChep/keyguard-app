package com.artemchep.keyguard.feature.navigation

import com.artemchep.keyguard.common.model.NavAnimation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationBackMotionRenderTest {
    @Test
    fun `a tablet stack pop measures the detail pane and leaves the master in place`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            start(
                twoPaneHost("panes", screen("list")),
                width = NavigationComposeHarness.WIDE_WIDTH,
                height = NavigationComposeHarness.WIDE_HEIGHT,
            )
            push("d1", routerId = "panes")
            push("d2", routerId = "panes")
            val master = screens.bounds("list")
            val detail = screens.bounds("d2")
            dragBack(1f)

            assertEquals(master, screens.bounds("list"))
            assertEquals(detail.width * PEEK_SCALE, screens.bounds("d2").width, 0.01f)
            assertEquals(screens.bounds("d2").width, screens.bounds("d1").width, 0.01f)

            assertTrue(host.handleBack())
            frame()

            assertEquals(master, screens.bounds("list"))
            assertEquals(detail, screens.bounds("d1"))
            assertFalse("d2" in screens.composed)
        }

    @Test
    fun `commit expands from the rendered pose without completing the drag first`() {
        for (progress in listOf(0f, 0.1f, 0.5f, 1f)) {
            withNavigationHarness(NavAnimation.DYNAMIC) {
                start(routerHost("outer", screen("list")))
                push("details")
                dragBack(progress)
                val top = screens.bounds("details")
                val under = screens.bounds("list")
                assertTrue(top.width <= WIDTH)
                assertEquals(top.width, under.width, 0.01f)

                assertTrue(host.handleBack())
                frame(frameNanos = 0)

                assertEquals(top.width, screens.bounds("details").width, 0.01f)
                assertEquals(top.left, screens.bounds("details").left, 0.01f)
                assertEquals(under.width, screens.bounds("list").width, 0.01f)
                var topWidth = top.width
                var underWidth = under.width
                repeat(5) {
                    render(frameNanos = 30_000_000L)
                    val nextTopWidth = screens.bounds("details").width
                    val nextUnderWidth = screens.bounds("list").width
                    assertTrue(nextTopWidth >= topWidth - 0.01f)
                    assertTrue(nextUnderWidth >= underWidth - 0.01f)
                    topWidth = nextTopWidth
                    underWidth = nextUnderWidth
                }
                assertTrue(screens.isLive("list"))
                assertTrue(screens.isFinishing("details"))

                frame()
                assertFalse("details" in screens.composed)
                assertEquals(WIDTH, screens.bounds("list").width)
                assertEquals(1, router("outer").entries.size)
            }
        }
    }

    @Test
    fun `another gesture can pop while the previous handoff is still running`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            start(routerHost("outer", screen("list")))
            push("d1")
            push("d2")
            host.startBack(event(0.5f))
            frame()
            assertTrue(host.handleBack())
            frame(frameNanos = 0)
            assertTrue(screens.isFinishing("d2"))

            dragBack(1f)

            assertEquals(WIDTH * PEEK_SCALE, screens.bounds("d1").width, 0.01f)
            assertEquals(WIDTH * PEEK_SCALE, screens.bounds("list").width, 0.01f)
            assertTrue(host.handleBack())
            frame()
            assertTrue(screens.isLive("list"))
            assertFalse("d1" in screens.composed)
            assertFalse("d2" in screens.composed)
            assertEquals(1, router("outer").entries.size)
        }

    @Test
    fun `a gesture after a finished pop starts from the screen at rest`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            start(routerHost("outer", screen("list")))
            push("d1")
            push("d2")
            dragBack(1f)
            assertTrue(host.handleBack())
            frame()
            assertFalse("d2" in screens.composed)

            host.startBack(event(0f))
            frame()

            assertEquals(WIDTH, screens.bounds("d1").width, 0.01f)
            assertEquals(0f, screens.bounds("d1").left, 0.01f)
        }

    @Test
    fun `vertical movement moves both layers and cancellation restores the screen`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            start(routerHost("outer", screen("list")))
            push("details")
            dragBack(1f, touchY = 400f)

            assertEquals(WIDTH * PEEK_SCALE, screens.bounds("details").width, 0.01f)
            assertEquals(screens.bounds("details").top, screens.bounds("list").top, 0.01f)
            // Below the top of a page that is scaled around its center.
            assertTrue(screens.bounds("details").top > HEIGHT * (1f - PEEK_SCALE) / 2f)

            host.cancelBack()
            frame()

            assertFalse("list" in screens.composed)
            assertEquals(0f, screens.bounds("details").top)
            assertEquals(WIDTH, screens.bounds("details").width)
            assertEquals(2, router("outer").entries.size)
        }

    private fun NavigationComposeHarness.push(name: String, routerId: String = "outer") {
        router(routerId).controller.queue(NavigationIntent.NavigateToRoute(screen(name)))
        frame()
    }

    /** Starts a Back gesture and drags it to the [progress]. */
    private fun NavigationComposeHarness.dragBack(progress: Float, touchY: Float = TOUCH_Y) {
        host.startBack(event(0f))
        frame()
        host.progressBack(event(progress).copy(touchY = touchY))
        frame()
    }

    private fun event(progress: Float) = NavigationBackEvent(
        progress = progress,
        swipeEdge = NavigationBackEdge.LEFT,
        touchX = 0f,
        touchY = TOUCH_Y,
    )

    private companion object {
        const val WIDTH = NavigationComposeHarness.NARROW_WIDTH.toFloat()
        const val HEIGHT = NavigationComposeHarness.NARROW_HEIGHT.toFloat()

        /** The scale of both pages at the end of a full drag. */
        const val PEEK_SCALE = 0.85f
        const val TOUCH_Y = 100f
    }
}
