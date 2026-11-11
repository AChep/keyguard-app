package com.artemchep.keyguard.feature.navigation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf

class NavigationBackMotionTest {
    @Test
    fun `full drag scales both pages and preserves the far edge margin`() {
        val motion = motion()
        val frame = motion.frame(NavigationAnimation.backGestureFraction(1f))
        val top = frame.transform(entering = false)
        val under = frame.transform(entering = true)

        assertEquals(0.85f, top.scale)
        assertEquals(top.scale, under.scale)
        assertEquals(1f, top.alpha)
        assertEquals(MARGIN, SIZE.width * (1f - top.scale) / 2f - top.offset.x, 0.001f)
        assertEquals(-ENTERING_OFFSET, under.offset.x)
        assertEquals(0f, frame.commitProgress)
    }

    @Test
    fun `right edge shrinks centrally instead of mirroring the left edge`() {
        val frame = motion(NavigationBackEdge.RIGHT).frame(NavigationAnimation.backGestureFraction(1f))

        assertEquals(0f, frame.transform(entering = false).offset.x)
        assertEquals(-ENTERING_OFFSET, frame.transform(entering = true).offset.x)
    }

    @Test
    fun `release begins at the actual pose for any drag length`() {
        for (progress in listOf(0f, 0.1f, 0.5f, 1f)) {
            val motion = motion()
            val fraction = NavigationAnimation.backGestureFraction(progress)
            val before = motion.frame(fraction)

            motion.commit(fraction)

            for (entering in listOf(false, true)) {
                assertEquals(before.transform(entering), motion.frame(fraction).transform(entering))
                val half = motion.frame(fraction + (1f - fraction) / 2f).transform(entering)
                assertTrue(half.scale >= before.transform(entering).scale)
            }
            val end = motion.frame(1f)
            assertEquals(1f, end.transform(entering = false).scale)
            assertEquals(0f, end.transform(entering = false).alpha)
            assertEquals(1f, end.transform(entering = true).scale)
            assertEquals(Offset.Zero, end.transform(entering = true).offset)
            assertEquals(0f, end.transform(entering = true).corner)
        }
    }

    @Test
    fun `vertical movement follows touch delta and stays inside the pane`() {
        for (touchY in listOf(-10_000f, 10_000f)) {
            val gesture = gesture()
            gesture.touchY = touchY
            val frame = NavigationBackMotion(gesture).frame(NavigationAnimation.backGestureFraction(1f))
            val top = frame.transform(entering = false)
            val under = frame.transform(entering = true)

            assertEquals(top.offset.y, under.offset.y)
            assertTrue(top.offset.y * touchY > 0f)
            val topMargin = SIZE.height * (1f - top.scale) / 2f + top.offset.y
            val bottomMargin = SIZE.height * (1f - top.scale) / 2f - top.offset.y
            assertTrue(topMargin >= MARGIN - 0.001f)
            assertTrue(bottomMargin >= MARGIN - 0.001f)
        }
    }

    @Test
    fun `release freezes touch displacement and settles back to zero`() {
        val gesture = gesture()
        gesture.touchY = 140f
        val motion = NavigationBackMotion(gesture)
        val fraction = NavigationAnimation.backGestureFraction(0.6f)
        val before = motion.frame(fraction).transform(entering = false)
        motion.commit(fraction)
        gesture.touchY = -1000f

        assertEquals(before, motion.frame(fraction).transform(entering = false))
        assertEquals(0f, motion.frame(1f).transform(entering = false).offset.y)
    }

    @Test
    fun `large panes increase the preview offset proportionally`() {
        val frame = motion().frame(NavigationAnimation.backGestureFraction(1f))
        val under = frame.transform(entering = true, size = Size(1000f, 700f))

        assertEquals(-150f, under.offset.x)
    }

    private fun NavigationBackFrame.transform(entering: Boolean, size: Size = SIZE) =
        transform(size, MARGIN, ENTERING_OFFSET, entering)

    private fun motion(edge: NavigationBackEdge = NavigationBackEdge.LEFT) = NavigationBackMotion(gesture(edge))

    private fun gesture(edge: NavigationBackEdge = NavigationBackEdge.LEFT) = NavigationBackGesture(
        from = NavigationStack("stack", persistentListOf()),
        to = NavigationStack("stack", persistentListOf()),
        swipeEdge = edge,
        progress = 0f,
        initialTouchY = 100f,
    )

    private companion object {
        val SIZE = Size(390f, 660f)
        const val MARGIN = 8f
        const val ENTERING_OFFSET = 96f
    }
}
