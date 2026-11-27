package com.artemchep.keyguard.feature.masterkdf

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.usecase.MeasureMasterKdfUseCase
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class MasterKdfFormStateProducerTest {
    @Test
    fun `initial values are the default`() =
        runTest {
            val fixture = fixture()

            val state = fixture.current()
            assertEquals("3", state.iterations.text)
            assertEquals("64", state.memory.text)
            assertEquals("4", state.parallelism.text)
            assertEquals(MasterKdf.Argon2id.DEFAULT, state.kdf)
            assertEquals(MasterKdf.Argon2id.DEFAULT, fixture.handle.currentKdf())
            assertNull(state.onReset)
            assertNotNull(state.test.onTest)
            assertNull(state.warning)
        }

    @Test
    fun `an invalid field gives no kdf and no test`() =
        runTest {
            val fixture = fixture()

            fixture.current().iterations.onChange!!.invoke("1")

            val state = fixture.current()
            assertNull(state.kdf)
            assertNull(fixture.handle.currentKdf())
            assertNotNull(state.iterations.error)
            assertNull(state.test.onTest)
            assertNotNull(state.onReset)
        }

    @Test
    fun `reset restores the default`() =
        runTest {
            val fixture = fixture()
            fixture.current().memory.onChange!!.invoke("128")
            assertEquals(131072, fixture.current().kdf?.memoryKib)

            fixture.current().onReset!!.invoke()

            val state = fixture.current()
            assertEquals(MasterKdf.Argon2id.DEFAULT, state.kdf)
            assertNull(state.onReset)
        }

    @Test
    fun `test is disabled while the screen is busy`() =
        runTest {
            val isBusy = MutableStateFlow(false)
            val fixture = fixture(isBusy = isBusy)
            val onTest = fixture.current().test.onTest!!

            isBusy.value = true
            assertNull(fixture.current().test.onTest)

            // A callback of an older state does not start a test either.
            onTest()
            runCurrent()
            assertTrue(fixture.measured.isEmpty())
            assertFalse(fixture.handle.isTestingFlow.value)
        }

    @Test
    fun `test runs one at a time`() =
        runTest {
            val gate = CompletableDeferred<Duration>()
            val fixture = fixture(measure = { gate.await() })
            val onTest = fixture.current().test.onTest!!

            onTest()
            runCurrent()
            val running = fixture.current()
            assertTrue(running.test.isRunning)
            assertNull(running.test.onTest)
            assertTrue(fixture.handle.isTestingFlow.value)

            onTest()
            runCurrent()
            assertEquals(1, fixture.measured.size)

            gate.complete(1200.milliseconds)
            runCurrent()
            val done = fixture.current()
            assertFalse(done.test.isRunning)
            assertFalse(fixture.handle.isTestingFlow.value)
            assertNotNull(done.test.onTest)
        }

    @Test
    fun `test result is shown only for the tested values`() =
        runTest {
            val fixture = fixture(measure = { 1200.milliseconds })

            fixture.current().test.onTest!!.invoke()
            runCurrent()
            val tested = fixture.current()
            assertEquals(listOf(MasterKdf.Argon2id.DEFAULT), fixture.measured)
            assertEquals(TRANSLATED, tested.test.resultText)
            assertEquals(listOf<Any?>("1.2"), fixture.scope.translatedArgs.last())

            tested.memory.onChange!!.invoke("128")
            val edited = fixture.current()
            assertNull(edited.test.resultText)
        }

    @Test
    fun `a new test clears the previous result`() =
        runTest {
            var gate = CompletableDeferred(1200.milliseconds)
            val fixture = fixture(measure = { gate.await() })
            fixture.current().test.onTest!!.invoke()
            runCurrent()
            assertEquals(TRANSLATED, fixture.current().test.resultText)

            gate = CompletableDeferred()
            fixture.current().test.onTest!!.invoke()
            runCurrent()
            val running = fixture.current()
            assertTrue(running.test.isRunning)
            assertNull(running.test.resultText)

            gate.complete(800.milliseconds)
            runCurrent()
            assertEquals(TRANSLATED, fixture.current().test.resultText)
        }

    @Test
    fun `test failure is reported as a message`() =
        runTest {
            val error = IllegalStateException("Out of memory")
            val fixture = fixture(measure = { throw error })

            fixture.current().test.onTest!!.invoke()
            runCurrent()

            assertSame(error, fixture.scope.errors.single())
            val state = fixture.current()
            assertNull(state.test.resultText)
            assertFalse(state.test.isRunning)
        }

    @Test
    fun `memory warning is shown on iOS above 64 MiB`() =
        runTest {
            val fixture = fixture(platform = Platform.Mobile.Ios.Native)
            assertNull(fixture.current().warning)

            fixture.current().memory.onChange!!.invoke("65")
            assertEquals(TRANSLATED, fixture.current().warning)

            fixture.current().memory.onChange!!.invoke("64")
            assertNull(fixture.current().warning)
        }

    @Test
    fun `memory warning is not shown on desktop`() =
        runTest {
            val fixture = fixture(platform = Platform.Desktop.Windows)

            fixture.current().memory.onChange!!.invoke("1024")

            assertNull(fixture.current().warning)
        }

    private class Fixture(
        val scope: MasterKdfTestScope,
        val handle: MasterKdfFormHandle,
        val measured: List<MasterKdf.Argon2id>,
    ) {
        suspend fun current() = handle.stateFlow.first()
    }

    // The screen scope is the background scope of the test: run its
    // work with runCurrent(), as advanceUntilIdle() skips it.
    private fun TestScope.fixture(
        isBusy: MutableStateFlow<Boolean> = MutableStateFlow(false),
        platform: Platform = Platform.Desktop.Windows,
        measure: suspend () -> Duration = { 1.milliseconds },
    ): Fixture {
        val scope = MasterKdfTestScope(backgroundScope)
        val measured = mutableListOf<MasterKdf.Argon2id>()
        val measureMasterKdf = object : MeasureMasterKdfUseCase {
            override fun invoke(kdf: MasterKdf.Argon2id): IO<Duration> = ioEffect {
                measured += kdf
                measure()
            }
        }
        val handle = scope.masterKdfFormHandle(
            prefix = "kdf",
            initial = MasterKdf.Argon2id.DEFAULT,
            measureMasterKdf = measureMasterKdf,
            isBusyFlow = isBusy,
            platform = platform,
        )
        return Fixture(
            scope = scope,
            handle = handle,
            measured = measured,
        )
    }
}
