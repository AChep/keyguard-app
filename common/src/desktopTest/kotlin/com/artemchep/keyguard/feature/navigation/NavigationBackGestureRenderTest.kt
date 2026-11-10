package com.artemchep.keyguard.feature.navigation

import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.feature.home.ResponsiveLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How [NavigationNode] renders a Back gesture: the preview of the
 * screen Back leads to, the release and the cancel.
 */
class NavigationBackGestureRenderTest {
    @Test
    fun `start composes the previous screen as finishing and keeps the current one live`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            start(routerHost("outer", screen("list")))
            push("details")
            assertFalse("list" in screens.composed)

            host.startBack(backEvent(0f))
            frame()
            assertTrue(screens.isFinishing("list"))
            assertTrue(screens.isLive("details"))

            host.progressBack(backEvent(0.5f))
            frame()
            assertTrue(screens.isFinishing("list"))
            assertTrue(screens.isLive("details"))
            assertEquals(2, router("outer").entries.size)
        }

    @Test
    fun `release keeps the composition of the previous screen and makes it live`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            start(routerHost("outer", screen("list")))
            push("details")
            host.startBack(backEvent(0f))
            frame()
            host.progressBack(backEvent(0.6f))
            frame()
            val compositions = screens.compositions("list")

            assertTrue(host.handleBack())
            render()
            // Live in the first frame after the pop.
            assertTrue(screens.isLive("list"))
            assertTrue(screens.isFinishing("details"))

            frame()
            assertTrue(screens.isLive("list"))
            assertFalse("details" in screens.composed)
            assertEquals(compositions, screens.compositions("list"))
            assertEquals(1, router("outer").entries.size)
        }

    @Test
    fun `cancel removes the preview and keeps the current screen`() =
        withNavigationHarness(NavAnimation.CROSSFADE) {
            start(routerHost("outer", screen("list")))
            push("details")
            val details = router("outer").entries.last()
            host.startBack(backEvent(0f))
            frame()
            host.progressBack(backEvent(0.8f))
            frame()
            assertTrue(screens.isFinishing("list"))

            host.cancelBack()
            frame()

            assertFalse("list" in screens.composed)
            assertTrue(screens.isLive("details"))
            assertEquals(1, screens.compositions("details"))
            assertEquals(2, router("outer").entries.size)
            assertFalse(details.isDestroyed)
        }

    @Test
    fun `a later gesture previews again after a cancel`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            start(routerHost("outer", screen("list")))
            push("details")
            host.startBack(backEvent(0.5f))
            frame()
            host.cancelBack()
            // The next gesture starts before the cancel played back.
            render()
            host.startBack(backEvent(0.25f))
            frame()
            assertTrue(screens.isFinishing("list"))
            assertTrue(screens.isLive("details"))

            assertTrue(host.handleBack())
            frame()
            assertTrue(screens.isLive("list"))
            assertFalse("details" in screens.composed)
        }

    @Test
    fun `with animations disabled the previous screen is not previewed`() =
        withNavigationHarness(NavAnimation.DISABLED) {
            start(routerHost("outer", screen("list")))
            push("details")

            host.startBack(backEvent(0f))
            frame()
            host.progressBack(backEvent(0.5f))
            frame()
            assertFalse("list" in screens.composed)
            assertTrue(screens.isLive("details"))

            assertTrue(host.handleBack())
            frame()
            assertTrue(screens.isLive("list"))
            assertFalse("details" in screens.composed)
            assertEquals(1, router("outer").entries.size)
        }

    @Test
    fun `a push during the gesture drops the preview and the release pops nothing`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            start(routerHost("outer", screen("list")))
            push("details")
            host.startBack(backEvent(0f))
            frame()
            host.progressBack(backEvent(0.5f))
            frame()
            assertTrue(screens.isFinishing("list"))

            push("more")
            assertFalse("list" in screens.composed)
            assertTrue(screens.isLive("more"))

            // Consumed, the pile committed during the gesture.
            assertTrue(host.handleBack())
            frame()
            assertEquals(3, router("outer").entries.size)
            assertTrue(screens.isLive("more"))
            assertFalse("details" in screens.composed)
            assertFalse("list" in screens.composed)
        }

    @Test
    fun `tab back previews the previous stack and the release makes it live`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            start(routerHost("outer", screen("tab1")))
            router("outer").controller.queue(NavigationIntent.NavigateToStack("tab2", screen("tab2")))
            frame()
            assertTrue(screens.isLive("tab2"))
            assertFalse("tab1" in screens.composed)
            val compositions = screens.compositions("tab1")

            host.startBack(backEvent(0f))
            frame()
            val gesture = assertNotNull(router("outer").back)
            assertNotEquals(gesture.from.id, gesture.to.id)
            assertTrue(screens.isFinishing("tab1"))
            assertTrue(screens.isLive("tab2"))

            host.progressBack(backEvent(0.5f))
            frame()
            assertTrue(host.handleBack())
            frame()

            assertTrue(screens.isLive("tab1"))
            assertFalse("tab2" in screens.composed)
            // Composed once, for the preview.
            assertEquals(compositions + 1, screens.compositions("tab1"))
        }

    @Test
    fun `a router in the previewed screen holds no Back target`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            start(routerHost("outer", routerHost("nested", screen("n1"))))
            router("nested").controller.queue(NavigationIntent.NavigateToRoute(screen("n2")))
            frame()
            push("details")
            assertFalse("n2" in screens.composed)

            host.startBack(backEvent(0f))
            frame()
            assertTrue(screens.isFinishing("n2"))
            assertTrue(router("nested").finishing)
            assertEquals(2, router("nested").entries.size)

            // Pick the target again. The nested router is deeper and could
            // pop, so it would win if the preview had registered it.
            host.startBack(backEvent(0f))
            assertTrue(host.handleBack())
            frame()

            assertEquals(1, router("outer").entries.size)
            assertEquals(2, router("nested").entries.size)
            assertTrue(screens.isLive("n2"))
            assertFalse(router("nested").finishing)
        }

    @Test
    fun `Back is consumed in the frame before the new content composes`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            start(routerHost("outer", backTargetScreen("old")))
            assertTrue(screens.isLive("old"))

            router("outer").controller.queue(NavigationIntent.SetRoute(backTargetScreen("new")))
            render()
            // The old screen left its Back target, the new one is not composed yet.
            assertTrue(screens.isFinishing("old"))
            assertFalse("new" in screens.composed)
            assertTrue(host.isBackEnabled)
            assertTrue(host.handleBack())
            assertEquals(emptyList(), screens.backs)

            frame()
            assertTrue(screens.isLive("new"))
            assertFalse("old" in screens.composed)
            assertTrue(host.handleBack())
            assertEquals(listOf("new"), screens.backs)
        }

    @Test
    fun `a node inside a layout subcomposition keeps the preview on release`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            assertReleaseKeepsPreviewInSubcomposition(progress = 0.6f)
        }

    @Test
    fun `a node inside a layout subcomposition keeps the preview on release at progress 0`() =
        withNavigationHarness(NavAnimation.DYNAMIC) {
            assertReleaseKeepsPreviewInSubcomposition(progress = 0f)
        }
}

// The helpers are top level, so the class stays
// within the detekt function limit.
private fun NavigationComposeHarness.assertReleaseKeepsPreviewInSubcomposition(
    progress: Float,
) {
    // The way Home renders its stack: the gesture is read next to
    // the stack, outside the subcomposition of the layout.
    start(
        routerHost("outer", screen("list")) { entries ->
            val back = LocalNavigationBackGesture.current
            ResponsiveLayout {
                NavigationNode(entries = entries, back = back)
            }
        },
    )
    push("details")
    host.startBack(backEvent(0f))
    frame()
    host.progressBack(backEvent(progress))
    frame()
    assertTrue(screens.isFinishing("list"))
    val compositions = screens.compositions("list")

    assertTrue(host.handleBack())
    frame()
    assertTrue(screens.isLive("list"))
    assertFalse("details" in screens.composed)
    assertEquals(compositions, screens.compositions("list"))
    assertEquals(1, router("outer").entries.size)
}

private fun NavigationComposeHarness.push(name: String) {
    router("outer").controller.queue(NavigationIntent.NavigateToRoute(screen(name)))
    frame()
}

private fun backEvent(progress: Float) = NavigationBackEvent(
    progress = progress,
    swipeEdge = NavigationBackEdge.LEFT,
    touchX = 0f,
    touchY = 0f,
)
