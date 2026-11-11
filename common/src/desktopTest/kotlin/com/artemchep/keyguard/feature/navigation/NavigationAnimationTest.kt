package com.artemchep.keyguard.feature.navigation

import androidx.compose.animation.EnterTransition
import com.artemchep.keyguard.common.model.NavAnimation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf

class NavigationAnimationTest {
    @Test
    fun `no progress seeks to the start`() {
        assertEquals(0f, NavigationAnimation.backGestureFraction(0f))
    }

    @Test
    fun `the fraction grows with the progress`() {
        val fractions = (0..STEPS).map { step ->
            NavigationAnimation.backGestureFraction(step.toFloat() / STEPS)
        }
        fractions.zipWithNext().forEach { (previous, next) ->
            assertTrue(next > previous, "$next must be above $previous")
        }
    }

    @Test
    fun `a full drag reaches the end of the drag part only`() {
        val fraction = NavigationAnimation.backGestureFraction(1f)
        assertEquals(BACK_GESTURE_DRAG_FRACTION, fraction)
        assertTrue(fraction > 0f && fraction < 1f)
    }

    @Test
    fun `progress out of range is clamped`() {
        assertEquals(0f, NavigationAnimation.backGestureFraction(-1f))
        assertEquals(0f, NavigationAnimation.backGestureFraction(Float.NaN))
        assertEquals(
            NavigationAnimation.backGestureFraction(1f),
            NavigationAnimation.backGestureFraction(2f),
        )
    }

    @Test
    fun `a dynamic back gesture delegates geometry to the peek layers`() {
        fun enter(
            animationType: NavAnimation,
            backEdge: NavigationBackEdge?,
        ) = NavigationAnimation
            .transform(
                scale = 1f,
                animationType = animationType,
                transitionType = NavigationAnimationType.GO_BACKWARD,
                backEdge = backEdge,
            )
            .targetContentEnter

        assertEquals(EnterTransition.None, enter(NavAnimation.DYNAMIC, NavigationBackEdge.LEFT))
        assertEquals(EnterTransition.None, enter(NavAnimation.DYNAMIC, NavigationBackEdge.NONE))
        assertNotEquals(EnterTransition.None, enter(NavAnimation.DYNAMIC, null))
        assertNotEquals(EnterTransition.None, enter(NavAnimation.CROSSFADE, NavigationBackEdge.LEFT))
    }

    @Test
    fun `crossfade gestures keep their existing seek curve`() {
        assertEquals(0.32142857f, NavigationAnimation.backGestureCrossfadeFraction(0.5f))
    }

    @Test
    fun `a gesture between two stacks switches the content`() {
        fun type(gesture: NavigationBackGesture?) = navigationBackTransitionType(gesture) {
            NavigationAnimationType.GO_BACKWARD
        }

        assertEquals(NavigationAnimationType.GO_BACKWARD, type(null))
        assertEquals(NavigationAnimationType.GO_BACKWARD, type(gesture(from = "tab", to = "tab")))
        assertEquals(NavigationAnimationType.SWITCH, type(gesture(from = "tab2", to = "tab1")))
    }

    private fun gesture(from: String, to: String) = NavigationBackGesture(
        from = NavigationStack(from, persistentListOf()),
        to = NavigationStack(to, persistentListOf()),
        swipeEdge = NavigationBackEdge.LEFT,
        progress = 0f,
    )

    private companion object {
        const val STEPS = 100
    }
}
