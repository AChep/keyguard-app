package com.artemchep.keyguard.desktop.startup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopTrayHostTest {
    @Test
    fun hostThatAppearsWithinGraceKeepsTheAppHiddenUntilItIsLost() = runTest {
        val trayHost = MutableStateFlow<Boolean?>(null)
        val result = async { trayHost.awaitTrayHostAbsence() }
        advanceTimeBy(TRAY_HOST_GRACE / 2)
        trayHost.value = false
        advanceTimeBy(1)
        trayHost.value = true
        advanceTimeBy(TRAY_HOST_GRACE * 2)
        runCurrent()
        assertFalse(result.isCompleted)

        trayHost.value = false
        runCurrent()
        assertTrue(result.isCompleted)
    }

    @Test
    fun noHostWithinGraceIsAbsent() = runTest {
        val trayHost = MutableStateFlow<Boolean?>(false)
        val result = async { trayHost.awaitTrayHostAbsence() }
        advanceTimeBy(TRAY_HOST_GRACE - 1.milliseconds)
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(2.milliseconds)
        runCurrent()
        assertTrue(result.isCompleted)
    }

    @Test
    fun unknownHostWithinGraceIsAbsent() = runTest {
        val trayHost = MutableStateFlow<Boolean?>(null)

        trayHost.awaitTrayHostAbsence()
    }
}
