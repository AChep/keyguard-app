package com.artemchep.keyguard.desktop.passkey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class WindowsPasskeyActivationTest {
    private class Process(
        val retained: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val busy: MutableStateFlow<Boolean> = MutableStateFlow(false),
        /** Replies of the native retirement, in order; later calls succeed. */
        val retirements: ArrayDeque<Boolean> = ArrayDeque(),
    ) {
        var retireCalls = 0
        var exited = false
    }

    private fun TestScope.launchProcess(process: Process = Process()): Process {
        backgroundScope.launch {
            awaitWindowsPasskeyActivationEnd(
                retained = process.retained,
                busy = process.busy,
                retire = {
                    process.retireCalls += 1
                    process.retirements.removeFirstOrNull() ?: true
                },
            )
            process.exited = true
        }
        runCurrent()
        return process
    }

    @Test
    fun `an activation without requests exits after the idle period`() = runTest {
        val process = launchProcess()
        advanceTimeBy(WINDOWS_PASSKEY_ACTIVATION_IDLE)
        assertFalse(process.exited)
        runCurrent()
        assertTrue(process.exited)
        assertEquals(1, process.retireCalls)
    }

    @Test
    fun `a request restarts the idle period`() = runTest {
        val process = launchProcess()
        advanceTimeBy(20.seconds)
        process.busy.value = true
        runCurrent()
        advanceTimeBy(5.minutes)
        assertFalse(process.exited)

        process.busy.value = false
        runCurrent()
        advanceTimeBy(WINDOWS_PASSKEY_ACTIVATION_IDLE)
        assertFalse(process.exited)
        runCurrent()
        assertTrue(process.exited)
    }

    @Test
    fun `a visible window or the tray keeps the process`() = runTest {
        val process = launchProcess(Process(retained = MutableStateFlow(true)))
        advanceTimeBy(1.minutes)
        runCurrent()
        assertFalse(process.exited)
        assertEquals(0, process.retireCalls)

        // For example, a quick search that the user dismissed.
        process.retained.value = false
        runCurrent()
        advanceTimeBy(WINDOWS_PASSKEY_ACTIVATION_IDLE)
        runCurrent()
        assertTrue(process.exited)
    }

    @Test
    fun `a refused retirement waits for another idle period`() = runTest {
        val process = launchProcess(Process(retirements = ArrayDeque(listOf(false))))
        advanceTimeBy(WINDOWS_PASSKEY_ACTIVATION_IDLE)
        runCurrent()
        assertFalse(process.exited)
        assertEquals(1, process.retireCalls)

        advanceTimeBy(WINDOWS_PASSKEY_ACTIVATION_IDLE)
        assertFalse(process.exited)
        runCurrent()
        assertTrue(process.exited)
        assertEquals(2, process.retireCalls)
    }

    @Test
    fun `a long sync stops holding the process`() = runTest {
        val syncing = MutableStateFlow(true)
        val values = mutableListOf<Boolean>()
        backgroundScope.launch {
            syncing.trueForAtMost(WINDOWS_PASSKEY_ACTIVATION_MAX_SYNC).collect { values += it }
        }
        runCurrent()
        assertEquals(listOf(true), values)
        advanceTimeBy(WINDOWS_PASSKEY_ACTIVATION_MAX_SYNC)
        runCurrent()
        assertEquals(listOf(true, false), values)

        // The next sync holds the process again.
        syncing.value = false
        runCurrent()
        syncing.value = true
        runCurrent()
        assertTrue(values.last())
    }
}
