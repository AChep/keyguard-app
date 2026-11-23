package com.artemchep.keyguard.desktop.startup

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginClearDataHook
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginRegistration
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginStatus
import com.artemchep.keyguard.common.usecase.impl.GetLaunchAtLoginImpl
import com.artemchep.keyguard.util.launchatlogin.LaunchAtLoginStatus as NativeStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLaunchAtLoginServiceTest {
    @Test
    fun refreshIsReadOnlyAndSettingsRequeriesEffectiveState() = runTest {
        val backend = Backend()
        val service = DesktopLaunchAtLoginService(Logs(), { backend }, StandardTestDispatcher(testScheduler))
        service.refresh()
        assertEquals(listOf("status"), backend.calls)
        service.openSettings()
        assertEquals(listOf("status", "settings", "status"), backend.calls)
        assertEquals(LaunchAtLoginStatus.DISABLED, service.state.value.status)
    }

    @Test
    fun statusIsUnreadUntilTheFirstSuccessfulRead() = runTest {
        val backend = Backend().apply { failure = IllegalStateException("fixture") }
        val service = DesktopLaunchAtLoginService(Logs(), { backend }, StandardTestDispatcher(testScheduler))
        assertNull(service.state.value.status)
        service.refresh()
        assertNull(service.state.value.status)
        assertTrue(service.state.value.failed)
        backend.failure = null
        service.refresh()
        assertEquals(LaunchAtLoginStatus.DISABLED, service.state.value.status)
    }

    @Test
    fun onlyTheStartupRepairRegistersAStaleEntryAgain() = runTest {
        val backend = Backend().apply { observed = LaunchAtLoginStatus.STALE }
        val service = DesktopLaunchAtLoginService(Logs(), { backend }, StandardTestDispatcher(testScheduler))
        service.refresh()
        assertEquals(listOf("status"), backend.calls)
        assertEquals(LaunchAtLoginStatus.STALE, service.state.value.status)
        service.repairStale()
        assertEquals(listOf("status", "status", "set:true"), backend.calls)
        assertEquals(LaunchAtLoginStatus.ENABLED, service.state.value.status)
        service.repairStale()
        assertEquals(listOf("status", "status", "set:true", "status"), backend.calls)
    }

    @Test
    fun failurePreservesObservedStatusClearsBusyAndAllowsRetry() = runTest {
        val backend = Backend().apply { observed = LaunchAtLoginStatus.ENABLED }
        val service = DesktopLaunchAtLoginService(Logs(), { backend }, StandardTestDispatcher(testScheduler))
        service.refresh()
        backend.failure = IllegalStateException("permission denied")
        service.setEnabled(false)
        assertEquals(LaunchAtLoginStatus.ENABLED, service.state.value.status)
        assertTrue(service.state.value.failed)
        assertFalse(service.state.value.busy)
        backend.failure = null
        service.setEnabled(false)
        assertEquals(LaunchAtLoginStatus.DISABLED, service.state.value.status)
        assertFalse(service.state.value.failed)
    }

    @Test
    fun cancellationReleasesMutexAndBusyWithoutReportingFailure() = runTest {
        val backend = Backend().apply { read = { awaitCancellation() } }
        val service = DesktopLaunchAtLoginService(Logs(), { backend }, StandardTestDispatcher(testScheduler))
        val first = launch { service.openSettings() }
        runCurrent()
        assertTrue(service.state.value.busy)
        first.cancelAndJoin()
        assertFalse(service.state.value.busy)
        assertFalse(service.state.value.failed)
        backend.read = { LaunchAtLoginStatus.DISABLED }
        service.refresh()
        assertEquals(LaunchAtLoginStatus.DISABLED, service.state.value.status)
    }

    @Test
    fun simultaneousChangesSerializeAndCancelledWaiterDoesNotChangeState() = runTest {
        val release = CompletableDeferred<Unit>()
        val backend = Backend().apply { read = { release.await(); LaunchAtLoginStatus.DISABLED } }
        var factories = 0
        val service = DesktopLaunchAtLoginService(
            Logs(),
            { factories++; backend },
            StandardTestDispatcher(testScheduler),
        )
        val settings = launch { service.openSettings() }
        runCurrent()
        val cancelled = launch { service.setEnabled(false) }
        val enable = launch { service.setEnabled(true) }
        runCurrent()
        assertEquals(listOf("settings", "status"), backend.calls)
        cancelled.cancelAndJoin()
        assertTrue(service.state.value.busy)
        release.complete(Unit)
        settings.join()
        enable.join()
        assertEquals(listOf("settings", "status", "set:true"), backend.calls)
        assertEquals(1, factories)
        assertEquals(LaunchAtLoginStatus.ENABLED, service.state.value.status)
        assertFalse(service.state.value.busy)
    }

    @Test
    fun failedCreationCanRetryAndMissingNativeLibraryIsVisible() = runTest {
        var factories = 0
        val service = DesktopLaunchAtLoginService(
            logRepository = Logs(),
            createRegistration = {
                if (++factories == 1) throw UnsatisfiedLinkError("fixture: missing JNI")
                Backend()
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        service.refresh()
        assertTrue(service.state.value.failed)
        assertFalse(service.state.value.busy)
        service.refresh()
        assertFalse(service.state.value.failed)
        assertEquals(LaunchAtLoginStatus.DISABLED, service.state.value.status)
    }

    @Test
    fun unavailableInstallationDoesNotReportEnabledOrFailed() = runTest {
        val service = DesktopLaunchAtLoginService(Logs(), { null }, StandardTestDispatcher(testScheduler))
        service.setEnabled(true)
        assertEquals(LaunchAtLoginStatus.UNAVAILABLE, service.state.value.status)
        assertFalse(service.state.value.failed)
        assertFalse(service.state.value.busy)
    }

    @Test
    fun passiveRefreshDoesNotPublishBusyAndSkipsWithoutObservers() = runTest {
        val backend = Backend()
        val service = DesktopLaunchAtLoginService(Logs(), { backend }, StandardTestDispatcher(testScheduler))
        val states = mutableListOf<Boolean>()
        service.refreshIfObserved()
        assertTrue(backend.calls.isEmpty())
        val observer = launch { service.state.collect { states += it.busy } }
        runCurrent()
        service.refreshIfObserved()
        runCurrent()
        assertEquals(listOf("status"), backend.calls)
        assertTrue(states.none { it })
        observer.cancelAndJoin()
    }

    @Test
    fun clearDataRemovesOnlyAnEnabledEntry() = runTest {
        val enabled = Backend().apply { observed = LaunchAtLoginStatus.ENABLED }
        val enabledService = DesktopLaunchAtLoginService(Logs(), { enabled }, StandardTestDispatcher(testScheduler))
        LaunchAtLoginClearDataHook(enabledService).beforeClearData()
        assertEquals(listOf("status", "set:false"), enabled.calls)
        assertEquals(LaunchAtLoginStatus.DISABLED, enabledService.state.value.status)

        // A stale entry is removed without registering it again first.
        val stale = Backend().apply { observed = LaunchAtLoginStatus.STALE }
        val staleService = DesktopLaunchAtLoginService(Logs(), { stale }, StandardTestDispatcher(testScheduler))
        LaunchAtLoginClearDataHook(staleService).beforeClearData()
        assertEquals(listOf("status", "set:false"), stale.calls)

        // On Flatpak every change is a portal request, so a disabled entry gets none.
        val disabled = Backend()
        val disabledService = DesktopLaunchAtLoginService(Logs(), { disabled }, StandardTestDispatcher(testScheduler))
        LaunchAtLoginClearDataHook(disabledService).beforeClearData()
        assertEquals(listOf("status"), disabled.calls)

        // There is nothing this app can remove, so erasing goes on.
        val unavailable = DesktopLaunchAtLoginService(Logs(), { null }, StandardTestDispatcher(testScheduler))
        LaunchAtLoginClearDataHook(unavailable).beforeClearData()
        val policy = Backend().apply { observed = LaunchAtLoginStatus.ENABLED_BY_POLICY }
        val policyService = DesktopLaunchAtLoginService(Logs(), { policy }, StandardTestDispatcher(testScheduler))
        LaunchAtLoginClearDataHook(policyService).beforeClearData()
        assertEquals(listOf("status"), policy.calls)
        val unreadable = Backend().apply { failure = IllegalStateException("fixture") }
        val unreadableService =
            DesktopLaunchAtLoginService(Logs(), { unreadable }, StandardTestDispatcher(testScheduler))
        LaunchAtLoginClearDataHook(unreadableService).beforeClearData()
        assertEquals(listOf("status"), unreadable.calls)
    }

    @Test
    fun clearDataReadsTheOsStatusNotTheLastPublishedOne() = runTest {
        val backend = Backend()
        val service = DesktopLaunchAtLoginService(Logs(), { backend }, StandardTestDispatcher(testScheduler))
        service.refresh()
        assertEquals(LaunchAtLoginStatus.DISABLED, service.state.value.status)
        // Turned on outside the app since the last read.
        backend.observed = LaunchAtLoginStatus.ENABLED
        LaunchAtLoginClearDataHook(service).beforeClearData()
        assertEquals(listOf("status", "status", "set:false"), backend.calls)
        assertEquals(LaunchAtLoginStatus.DISABLED, service.state.value.status)
    }

    @Test
    fun clearDataStopsTheEraseWhenRemovalFails() = runTest {
        val backend = Backend().apply { observed = LaunchAtLoginStatus.ENABLED }
        val service = DesktopLaunchAtLoginService(Logs(), { backend }, StandardTestDispatcher(testScheduler))
        service.refresh()
        backend.setFailure = IllegalStateException("portal timed out")
        assertFailsWith<IllegalStateException> { LaunchAtLoginClearDataHook(service).beforeClearData() }
        assertEquals(LaunchAtLoginStatus.ENABLED, service.state.value.status)
        assertTrue(service.state.value.failed)
        // The user can retry the erase.
        backend.setFailure = null
        LaunchAtLoginClearDataHook(service).beforeClearData()
        assertEquals(LaunchAtLoginStatus.DISABLED, service.state.value.status)
    }

    @Test
    fun failedRepairKeepsTheStaleStatusSoItCanBeTurnedOff() = runTest {
        val logs = Logs()
        val backend = Backend().apply {
            observed = LaunchAtLoginStatus.STALE
            setFailure = IllegalStateException("fixture: read-only autostart")
        }
        val service = DesktopLaunchAtLoginService(logs, { backend }, StandardTestDispatcher(testScheduler))
        service.repairStale()
        assertEquals(listOf("status", "set:true"), backend.calls)
        assertEquals(LaunchAtLoginStatus.STALE, service.state.value.status)
        assertTrue(service.state.value.failed)
        assertTrue(service.state.value.status!!.canChange)
        val entry = logs.entries.single()
        assertEquals(LogLevel.WARNING, entry.level)
        assertTrue("repair" in entry.message, entry.message)
        assertTrue("IllegalStateException: fixture: read-only autostart" in entry.message, entry.message)
    }

    @Test
    fun failureIsLoggedWithTheOperationAndCause() = runTest {
        val logs = Logs()
        val backend = Backend().apply { failure = IllegalStateException("permission denied") }
        val service = DesktopLaunchAtLoginService(logs, { backend }, StandardTestDispatcher(testScheduler))
        service.setEnabled(true)
        val entry = logs.entries.single()
        assertEquals(LogLevel.WARNING, entry.level)
        assertTrue("enable" in entry.message, entry.message)
        assertTrue("IllegalStateException: permission denied" in entry.message, entry.message)
    }

    @Test
    fun getLaunchAtLoginShowsTheLastStateAtOnceAndRefreshes() = runTest {
        val release = CompletableDeferred<Unit>()
        val backend = Backend().apply { read = { release.await(); LaunchAtLoginStatus.ENABLED } }
        val service = DesktopLaunchAtLoginService(Logs(), { backend }, StandardTestDispatcher(testScheduler))
        val states = mutableListOf<LaunchAtLoginStatus?>()
        val job = launch { GetLaunchAtLoginImpl(service)().collect { states += it.status } }
        runCurrent()
        assertEquals(listOf<LaunchAtLoginStatus?>(null), states)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(null, LaunchAtLoginStatus.ENABLED), states)
        job.cancelAndJoin()
    }

    @Test
    fun nativeStatusesMapOneToOne() {
        for (status in NativeStatus.entries) {
            assertEquals(status.name, status.toLaunchAtLoginStatus().name)
        }
    }

    private class Backend : LaunchAtLoginRegistration {
        var observed = LaunchAtLoginStatus.DISABLED
        var failure: Exception? = null
        var setFailure: Exception? = null
        var read: suspend () -> LaunchAtLoginStatus = { observed }
        val calls = mutableListOf<String>()
        override suspend fun status(): LaunchAtLoginStatus {
            calls += "status"
            failure?.let { throw it }
            return read()
        }
        override suspend fun setEnabled(enabled: Boolean): LaunchAtLoginStatus {
            calls += "set:$enabled"
            (failure ?: setFailure)?.let { throw it }
            observed = if (enabled) LaunchAtLoginStatus.ENABLED else LaunchAtLoginStatus.DISABLED
            return observed
        }
        override suspend fun openSettings() {
            calls += "settings"
            failure?.let { throw it }
        }
    }

    private class Logs : LogRepository {
        val entries = mutableListOf<Entry>()

        override fun post(tag: String, message: String, level: LogLevel) {
            entries += Entry(message, level)
        }

        override suspend fun add(tag: String, message: String, level: LogLevel) = post(tag, message, level)

        data class Entry(val message: String, val level: LogLevel)
    }
}
