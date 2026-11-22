package com.artemchep.keyguard.feature.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.artemchep.keyguard.ui.draw
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.withScene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class AgentApprovalInputGuardTest {
    /** Simulates the window focus; relaunch to change it. */
    private fun TestScope.launchRun(
        guard: AgentApprovalInputGuard,
        focused: Boolean,
    ): Job = backgroundScope.launch {
        guard.run(focused)
    }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    @Test
    fun `arms after one second of focus with no input`() = runTest {
        val guard = AgentApprovalInputGuard()
        launchRun(guard, focused = true)
        runCurrent()
        assertFalse(guard.armed)

        advance(999.milliseconds)
        assertFalse(guard.armed)
        advance(1.milliseconds)
        assertTrue(guard.armed)
    }

    @Test
    fun `input while held back restarts the wait`() = runTest {
        val guard = AgentApprovalInputGuard()
        launchRun(guard, focused = true)
        runCurrent()

        advance(500.milliseconds)
        guard.onInput()
        runCurrent()

        advance(500.milliseconds)
        assertFalse(guard.armed)
        advance(500.milliseconds)
        assertTrue(guard.armed)
    }

    @Test
    fun `input after arming keeps the guard armed`() = runTest {
        val guard = AgentApprovalInputGuard()
        launchRun(guard, focused = true)
        advance(1.seconds)
        assertTrue(guard.armed)

        guard.onInput()
        runCurrent()
        assertTrue(guard.armed)
        advance(500.milliseconds)
        assertTrue(guard.armed)
    }

    @Test
    fun `a press held past the deadline keeps the guard held back`() = runTest {
        val guard = AgentApprovalInputGuard()
        launchRun(guard, focused = true)
        runCurrent()

        advance(500.milliseconds)
        guard.onPress()
        runCurrent()
        advance(5.seconds)
        assertFalse(guard.armed)
    }

    @Test
    fun `the release of a held back press restarts the wait`() = runTest {
        val guard = AgentApprovalInputGuard()
        launchRun(guard, focused = true)
        runCurrent()

        guard.onPress()
        runCurrent()
        advance(2.seconds)
        guard.onRelease()
        runCurrent()

        advance(999.milliseconds)
        assertFalse(guard.armed)
        advance(1.milliseconds)
        assertTrue(guard.armed)
    }

    @Test
    fun `a press after arming keeps the guard armed`() = runTest {
        val guard = AgentApprovalInputGuard()
        launchRun(guard, focused = true)
        advance(1.seconds)
        assertTrue(guard.armed)

        guard.onPress()
        runCurrent()
        advance(5.seconds)
        assertTrue(guard.armed)
        guard.onRelease()
        runCurrent()
        assertTrue(guard.armed)
    }

    @Test
    fun `losing focus ends a held back press`() = runTest {
        val guard = AgentApprovalInputGuard()
        val focused = launchRun(guard, focused = true)
        runCurrent()
        guard.onPress()
        runCurrent()
        advance(2.seconds)
        assertFalse(guard.armed)

        // The release never comes.
        focused.cancel()
        launchRun(guard, focused = false)
        runCurrent()
        launchRun(guard, focused = true)
        runCurrent()
        advance(999.milliseconds)
        assertFalse(guard.armed)
        advance(1.milliseconds)
        assertTrue(guard.armed)
    }

    @Test
    fun `an unfocused window never arms and arms one second after focus`() = runTest {
        val guard = AgentApprovalInputGuard()
        val unfocused = launchRun(guard, focused = false)
        advance(5.seconds)
        assertFalse(guard.armed)

        unfocused.cancel()
        launchRun(guard, focused = true)
        runCurrent()
        advance(999.milliseconds)
        assertFalse(guard.armed)
        advance(1.milliseconds)
        assertTrue(guard.armed)
    }

    @Test
    fun `losing focus holds an armed guard back`() = runTest {
        val guard = AgentApprovalInputGuard()
        val focused = launchRun(guard, focused = true)
        advance(1.seconds)
        assertTrue(guard.armed)

        focused.cancel()
        launchRun(guard, focused = false)
        runCurrent()
        assertFalse(guard.armed)
        advance(5.seconds)
        assertFalse(guard.armed)
    }

    @Test
    fun `restart holds an armed guard back for one second`() = runTest {
        val guard = AgentApprovalInputGuard()
        launchRun(guard, focused = true)
        advance(1.seconds)
        assertTrue(guard.armed)

        guard.restart()
        assertFalse(guard.armed)
        runCurrent()
        advance(999.milliseconds)
        assertFalse(guard.armed)
        advance(1.milliseconds)
        assertTrue(guard.armed)
    }

    @Test
    fun `fresh buttons start held back even if the guard is armed`() {
        val guard = AgentApprovalInputGuard()
        runTest {
            launchRun(guard, focused = true)
            advance(1.seconds)
        }
        assertTrue(guard.armed)

        // Nothing runs the guard here, so it can't arm again.
        val composed = mutableListOf<Boolean>()
        withScene(
            content = {
                CompositionLocalProvider(
                    LocalAgentApprovalInputGuard provides guard,
                ) {
                    composed += rememberAgentApprovalArmed()
                }
            },
        ) {
            assertEquals(false, composed.firstOrNull())
            assertFalse(true in composed)
        }
    }

    @Test
    fun `only the first arming of a prompt moves the focus`() {
        val guard = AgentApprovalInputGuard()
        // Runs the guard on virtual time, driven from the scene's thread.
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        val other = FocusRequester()
        var requestId by mutableIntStateOf(0)
        withScene(
            content = {
                CompositionLocalProvider(
                    LocalAgentApprovalInputGuard provides guard,
                ) {
                    Column {
                        TextButton(
                            modifier = Modifier
                                .focusRequester(other),
                            onClick = {},
                        ) {
                            Text("Other")
                        }
                        key(requestId) {
                            AgentApprovalButtons(
                                expiresAt = null,
                                cancelText = "Cancel",
                                confirmText = "Confirm",
                                onCancel = {},
                                onConfirm = {},
                            )
                        }
                    }
                }
            },
        ) {
            fun arm() {
                scheduler.advanceTimeBy(1.seconds)
                scheduler.runCurrent()
                draw()
                assertTrue(guard.armed)
            }
            scope.launch { guard.run(focused = true) }
            arm()
            assertEquals(listOf("Confirm"), focusedTexts())

            other.requestFocus()
            draw()
            assertEquals(listOf("Other"), focusedTexts())

            // For example, the window lost and regained focus.
            guard.restart()
            scheduler.runCurrent()
            draw()
            assertFalse(guard.armed)
            arm()
            assertEquals(listOf("Other"), focusedTexts())

            // The buttons of the next request take focus again.
            requestId++
            draw()
            arm()
            assertEquals(listOf("Confirm"), focusedTexts())
        }
        scope.cancel()
    }

    @Test
    fun `the buttons of a next step keep the guard armed and the focus`() {
        val guard = AgentApprovalInputGuard()
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        val other = FocusRequester()
        var nextStep by mutableStateOf(false)
        withScene(
            content = {
                CompositionLocalProvider(
                    LocalAgentApprovalInputGuard provides guard,
                ) {
                    Column {
                        TextButton(
                            modifier = Modifier
                                .focusRequester(other),
                            onClick = {},
                        ) {
                            Text("Other")
                        }
                        // As the step of a prompt replaces the one before.
                        key(nextStep) {
                            AgentApprovalButtons(
                                expiresAt = null,
                                cancelText = "Cancel",
                                confirmText = "Confirm",
                                onCancel = {},
                                onConfirm = {},
                                nextStep = nextStep,
                            )
                        }
                    }
                }
            },
        ) {
            scope.launch { guard.run(focused = true) }
            scheduler.advanceTimeBy(1.seconds)
            scheduler.runCurrent()
            draw()
            assertTrue(guard.armed)
            assertEquals(listOf("Confirm"), focusedTexts())

            other.requestFocus()
            nextStep = true
            draw()
            scheduler.runCurrent()
            draw()
            assertTrue(guard.armed)
            assertEquals(listOf("Other"), focusedTexts())
        }
        scope.cancel()
    }
}
