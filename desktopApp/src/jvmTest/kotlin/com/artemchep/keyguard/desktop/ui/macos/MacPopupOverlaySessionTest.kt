package com.artemchep.keyguard.desktop.ui.macos

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MacPopupOverlaySessionTest {
    @Test
    fun `transient activation failure retries and makes the popup visible`() = runTest {
        withHarness {
            var attempts = 0
            var closed = 0
            lateinit var active: State<Boolean>
            val composition = compose {
                active = rememberMacPopupOverlaySession(visible = true) {
                    if (++attempts == 1) null else AutoCloseable { closed++ }
                }
            }
            frame()
            assertFalse(active.value)
            advanceTimeBy(1_000)
            frame()

            assertTrue(active.value)
            assertEquals(2, attempts)
            composition.dispose()
            frame()
            assertEquals(1, closed)
        }
    }

    @Test
    fun `failed retries are bounded and a new request can retry`() = runTest {
        withHarness {
            val requestKey = mutableStateOf("first")
            var attempts = 0
            var available = false
            lateinit var active: State<Boolean>
            compose {
                active = rememberMacPopupOverlaySession(true, requestKey.value) {
                    attempts++
                    if (available) AutoCloseable {} else null
                }
            }
            frame()
            advanceTimeBy(10_000)
            frame()
            assertFalse(active.value)
            val exhaustedAttempts = attempts
            assertTrue(exhaustedAttempts in 2..10)
            advanceTimeBy(10_000)
            frame()
            assertEquals(exhaustedAttempts, attempts)

            available = true
            requestKey.value = "next"
            frame()
            assertTrue(active.value)
            assertEquals(exhaustedAttempts + 1, attempts)

            requestKey.value = "later"
            frame()
            assertTrue(active.value)
            assertEquals(exhaustedAttempts + 1, attempts)
        }
    }

    @Test
    fun `hiding the popup cancels activation retries`() = runTest {
        withHarness {
            val visible = mutableStateOf(true)
            var attempts = 0
            compose {
                rememberMacPopupOverlaySession(visible.value) {
                    attempts++
                    null
                }
            }
            frame()
            visible.value = false
            frame()
            advanceTimeBy(10_000)
            frame()

            assertEquals(1, attempts)
        }
    }

    @Test
    fun `a native session returned after disposal is closed`() = runTest {
        withHarness {
            var continuation: Continuation<AutoCloseable?>? = null
            var closed = 0
            lateinit var active: State<Boolean>
            val composition = compose {
                active = rememberMacPopupOverlaySession(visible = true) {
                    suspendCoroutine { continuation = it }
                }
            }
            frame()
            composition.dispose()
            assertNotNull(continuation).resume(AutoCloseable { closed++ })
            frame()

            assertFalse(active.value)
            assertEquals(1, closed)
        }
    }

    private suspend fun TestScope.withHarness(block: suspend Harness.() -> Unit) {
        val harness = Harness(this)
        try {
            harness.block()
        } finally {
            harness.close()
        }
    }

    private class Harness(private val testScope: TestScope) {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(testScope.backgroundScope.coroutineContext + clock)
        private val runner = testScope.backgroundScope.launch(clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private val compositions = mutableListOf<Composition>()
        private var frameTime = 0L

        fun compose(content: @Composable () -> Unit): Composition =
            Composition(NoOpApplier(), recomposer).also { composition ->
                compositions += composition
                composition.setContent(content)
            }

        fun frame() {
            Snapshot.sendApplyNotifications()
            testScope.runCurrent()
            Snapshot.sendApplyNotifications()
            testScope.runCurrent()
            clock.sendFrame(++frameTime)
            testScope.runCurrent()
        }

        suspend fun close() {
            compositions.forEach(Composition::dispose)
            recomposer.cancel()
            runner.cancelAndJoin()
        }
    }

    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
