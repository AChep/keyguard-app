package com.artemchep.keyguard

import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginStatus
import com.artemchep.keyguard.util.launchatlogin.LaunchAtLoginStatus as NativeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MacosLaunchAtLoginServiceTest {
    @Test
    fun refreshOnlyReadsAndStartupRepairRegistersOnlyStale() = runTest {
        var status = NativeStatus.UNKNOWN
        val writes = mutableListOf<Boolean>()
        val service = MacosLaunchAtLoginService(
            readStatus = { status },
            writeEnabled = { writes += it; NativeStatus.ENABLED },
            showSettings = {},
            logRepository = LogRepositoryBridge(emptyList()),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        assertEquals(null, service.state.value.status)
        NativeStatus.entries.forEach {
            status = it
            service.refresh()
            assertEquals(LaunchAtLoginStatus.valueOf(it.name), service.state.value.status)
            service.repairStale()
            val expected = if (it == NativeStatus.STALE) NativeStatus.ENABLED else it
            assertEquals(LaunchAtLoginStatus.valueOf(expected.name), service.state.value.status)
        }
        assertEquals(listOf(true), writes)
    }

    @Test
    fun failurePreservesEffectiveStateAndLaterRefreshRecovers() = runTest {
        var fail = false
        var opened = 0
        val service = MacosLaunchAtLoginService(
            readStatus = { if (fail) error("Unavailable") else NativeStatus.ENABLED },
            writeEnabled = { error("Denied") },
            showSettings = { opened++ },
            logRepository = LogRepositoryBridge(emptyList()),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        service.refresh()
        service.setEnabled(false)
        assertTrue(service.state.value.failed)
        assertFalse(service.state.value.busy)
        assertEquals(LaunchAtLoginStatus.ENABLED, service.state.value.status)
        fail = true
        service.openSettings()
        assertEquals(1, opened)
        assertTrue(service.state.value.failed)
        fail = false
        service.refresh()
        assertFalse(service.state.value.failed)
    }

    @Test
    fun writesAreSerializedBusyAndCancellationClearsBusy() = runTest {
        val operations = mutableListOf<Boolean>()
        val service = MacosLaunchAtLoginService(
            readStatus = { throw CancellationException("Closing") },
            writeEnabled = { operations += it; if (it) NativeStatus.REQUIRES_APPROVAL else NativeStatus.DISABLED },
            showSettings = {},
            logRepository = LogRepositoryBridge(emptyList()),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val first = launch(start = CoroutineStart.UNDISPATCHED) { service.setEnabled(true) }
        assertTrue(service.state.value.busy)
        val second = launch { service.setEnabled(false) }
        first.join()
        second.join()
        assertEquals(listOf(true, false), operations)
        assertEquals(LaunchAtLoginStatus.DISABLED, service.state.value.status)
        val cancelled = launch { service.refresh() }
        runCurrent()
        assertTrue(cancelled.isCancelled)
        assertFalse(service.state.value.busy)
        assertFalse(service.state.value.failed)
    }
}
