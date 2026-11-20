package com.artemchep.keyguard.common.service.release.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AppRelease
import com.artemchep.keyguard.common.model.AppReleaseCache
import com.artemchep.keyguard.common.model.AppSemVer
import com.artemchep.keyguard.common.model.AppUpdateLevel
import com.artemchep.keyguard.common.model.DAppUpdate
import com.artemchep.keyguard.common.model.DNotification
import com.artemchep.keyguard.common.model.DNotificationChannel
import com.artemchep.keyguard.common.model.DNotificationId
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.release.AppReleaseRepository
import com.artemchep.keyguard.common.service.release.FakeAppUpdateSettings
import com.artemchep.keyguard.common.usecase.GetAppUpdate
import com.artemchep.keyguard.common.usecase.ShowNotification
import com.artemchep.keyguard.common.usecase.impl.GetAppUpdateLevelImpl
import com.artemchep.keyguard.test.RecordingLogRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateWorkerImplTest {
    private val start = Instant.parse("2026-11-20T12:00:00Z")

    private val initialDelay = AppUpdateWorkerImpl.INITIAL_DELAY

    private val checkInterval = AppUpdateWorkerImpl.CHECK_INTERVAL

    private val retryInterval = AppUpdateWorkerImpl.RETRY_INTERVAL

    private val release = AppRelease(
        version = AppSemVer(3, 9, 0),
        tag = "r20261101",
        publishedAt = Instant.parse("2026-11-01T10:00:00Z"),
        url = "https://github.com/AChep/keyguard-app/releases/tag/r20261101",
    )

    @Test
    fun `off makes no request`() = runTest {
        val settings = FakeAppUpdateSettings()
        settings.level.value = AppUpdateLevel.OFF
        val repository = FakeAppReleaseRepository()
        createWorker(settings, repository).launch(backgroundScope)

        runCurrent()
        advanceTimeBy(checkInterval * 3)
        runCurrent()
        assertEquals(0, repository.calls)
    }

    @Test
    fun `checks at most once per interval`() = runTest {
        val settings = FakeAppUpdateSettings()
        val repository = FakeAppReleaseRepository()
        createWorker(settings, repository).launch(backgroundScope)

        // The first check waits for the app to load.
        runCurrent()
        advanceTimeBy(initialDelay - 1.milliseconds)
        runCurrent()
        assertEquals(0, repository.calls)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(1, repository.calls)
        assertEquals(AppReleaseCache(lastCheckedAt = now(), releases = listOf(release)), settings.cache.value)

        advanceTimeBy(checkInterval - 1.milliseconds)
        runCurrent()
        assertEquals(1, repository.calls)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(2, repository.calls)
        assertEquals(now(), settings.cache.value?.lastCheckedAt)
    }

    @Test
    fun `waits for the interval since the last check`() = runTest {
        val settings = FakeAppUpdateSettings()
        settings.cache.value = AppReleaseCache(lastCheckedAt = start - 23.hours)
        val repository = FakeAppReleaseRepository()
        createWorker(settings, repository).launch(backgroundScope)

        advanceTimeBy(initialDelay)
        runCurrent()
        assertEquals(0, repository.calls)

        advanceTimeBy(1.hours - initialDelay)
        runCurrent()
        assertEquals(1, repository.calls)
    }

    @Test
    fun `checks at once if the last check is in the future`() = runTest {
        val settings = FakeAppUpdateSettings()
        settings.cache.value = AppReleaseCache(lastCheckedAt = start + 30.hours)
        val repository = FakeAppReleaseRepository()
        createWorker(settings, repository).launch(backgroundScope)

        advanceTimeBy(initialDelay)
        runCurrent()
        assertEquals(1, repository.calls)
    }

    @Test
    fun `a failed check keeps the cache and retries later`() = runTest {
        val settings = FakeAppUpdateSettings()
        val oldCache = AppReleaseCache(
            lastCheckedAt = start - 25.hours,
            releases = listOf(release),
        )
        settings.cache.value = oldCache
        val repository = FakeAppReleaseRepository(failures = 1)
        val logs = RecordingLogRepository()
        createWorker(settings, repository, logs = logs).launch(backgroundScope)

        advanceTimeBy(initialDelay)
        runCurrent()
        assertEquals(1, repository.calls)
        assertEquals(oldCache, settings.cache.value)
        assertEquals(LogLevel.WARNING, logs.entries.single().level)

        advanceTimeBy(retryInterval - 1.milliseconds)
        runCurrent()
        assertEquals(1, repository.calls)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(2, repository.calls)
        assertEquals(now(), settings.cache.value?.lastCheckedAt)
    }

    @Test
    fun `turning the checks off stops them`() = runTest {
        val settings = FakeAppUpdateSettings()
        val repository = FakeAppReleaseRepository()
        createWorker(settings, repository).launch(backgroundScope)

        advanceTimeBy(initialDelay)
        runCurrent()
        assertEquals(1, repository.calls)

        settings.level.value = AppUpdateLevel.OFF
        advanceTimeBy(checkInterval * 2)
        runCurrent()
        assertEquals(1, repository.calls)

        // The last check is stale by now.
        settings.level.value = AppUpdateLevel.MAJOR
        runCurrent()
        assertEquals(2, repository.calls)

        // Switching between the levels that check
        // does not start a new check.
        settings.level.value = AppUpdateLevel.MINOR
        runCurrent()
        assertEquals(2, repository.calls)
    }

    @Test
    fun `notifies once per release`() = runTest {
        val settings = FakeAppUpdateSettings()
        settings.level.value = AppUpdateLevel.OFF
        val updates = MutableStateFlow<DAppUpdate?>(null)
        val notifications = mutableListOf<DNotification>()
        createWorker(
            settings = settings,
            repository = FakeAppReleaseRepository(),
            updates = updates,
            notifications = notifications,
        ).launch(backgroundScope)
        runCurrent()
        assertTrue(notifications.isEmpty())

        val first = DAppUpdate(version = "3.9.0", url = "https://example.com/1", eligibleTag = "r20261101")
        updates.value = first
        settings.awaitNotifiedTag("r20261101")
        assertEquals(listOf(notification(first)), notifications)

        updates.value = null
        runCurrent()
        updates.value = first
        runCurrent()
        assertEquals(1, notifications.size)

        val second = DAppUpdate(version = "3.9.1", url = null, eligibleTag = "r20261105")
        updates.value = second
        settings.awaitNotifiedTag("r20261105")
        assertEquals(listOf(notification(first), notification(second)), notifications)
    }

    @Test
    fun `a newer release inside the delay does not notify again`() = runTest {
        val settings = FakeAppUpdateSettings()
        settings.level.value = AppUpdateLevel.OFF
        val updates = MutableStateFlow<DAppUpdate?>(null)
        val notifications = mutableListOf<DNotification>()
        createWorker(
            settings = settings,
            repository = FakeAppReleaseRepository(),
            updates = updates,
            notifications = notifications,
        ).launch(backgroundScope)

        val first = DAppUpdate(version = "3.9.0", url = null, eligibleTag = "r20261101")
        updates.value = first
        settings.awaitNotifiedTag("r20261101")
        // 3.9.1 is out but too fresh: the app shows it, the
        // newest release past the delay is still 3.9.0.
        updates.value = DAppUpdate(version = "3.9.1", url = null, eligibleTag = "r20261101")
        runCurrent()
        assertEquals(listOf(notification(first)), notifications)
        assertEquals("r20261101", settings.notifiedTag.value)
    }

    @Test
    fun `does not notify about a release it already notified about`() = runTest {
        val settings = FakeAppUpdateSettings()
        settings.level.value = AppUpdateLevel.OFF
        settings.notifiedTag.value = "r20261101"
        val notifications = mutableListOf<DNotification>()
        createWorker(
            settings = settings,
            repository = FakeAppReleaseRepository(),
            updates = MutableStateFlow(
                DAppUpdate(version = "3.9.0", url = null, eligibleTag = "r20261101"),
            ),
            notifications = notifications,
        ).launch(backgroundScope)

        runCurrent()
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `a failed notification is retried with the next update`() = runTest {
        val settings = FakeAppUpdateSettings()
        settings.level.value = AppUpdateLevel.OFF
        val updates = MutableStateFlow<DAppUpdate?>(null)
        val logs = RecordingLogRepository()
        var fail = true
        val attempts = MutableStateFlow(0)
        val notifications = mutableListOf<DNotification>()
        createWorker(
            settings = settings,
            repository = FakeAppReleaseRepository(),
            updates = updates,
            notifications = notifications,
            logs = logs,
            showNotification = { notification ->
                ioEffect {
                    attempts.value += 1
                    if (fail) error("No notification permission")
                    notifications += notification
                }
            },
        ).launch(backgroundScope)

        val update = DAppUpdate(version = "3.9.0", url = null, eligibleTag = "r20261101")
        updates.value = update
        // The notification is created on another dispatcher.
        attempts.first { it == 1 }
        runCurrent()
        assertTrue(notifications.isEmpty())
        assertEquals(null, settings.notifiedTag.value)
        assertEquals(LogLevel.WARNING, logs.entries.single().level)

        fail = false
        updates.value = null
        runCurrent()
        updates.value = update
        settings.awaitNotifiedTag("r20261101")
        assertEquals(listOf(notification(update)), notifications)
    }

    private fun TestScope.now() = start + testScheduler.currentTime.milliseconds

    /**
     * The worker creates the notification on another dispatcher,
     * so wait for it rather than for the test scheduler.
     */
    private suspend fun FakeAppUpdateSettings.awaitNotifiedTag(tag: String) {
        notifiedTag.first { it == tag }
    }

    private fun notification(update: DAppUpdate) = DNotification(
        id = DNotificationId.APP_UPDATE,
        title = "Update available",
        text = update.version,
        channel = DNotificationChannel.APP_UPDATES,
        url = update.url,
    )

    private fun TestScope.createWorker(
        settings: FakeAppUpdateSettings,
        repository: AppReleaseRepository,
        updates: Flow<DAppUpdate?> = MutableStateFlow(null),
        notifications: MutableList<DNotification> = mutableListOf(),
        logs: RecordingLogRepository = RecordingLogRepository(),
        showNotification: (DNotification) -> IO<Unit> = { notification ->
            ioEffect { notifications += notification }
        },
    ) = AppUpdateWorkerImpl(
        getAppUpdateLevel = GetAppUpdateLevelImpl(settings),
        getAppUpdate = object : GetAppUpdate {
            override fun invoke() = updates
        },
        settingsReadWriteRepository = settings,
        appReleaseRepository = repository,
        showNotification = object : ShowNotification {
            override fun invoke(notification: DNotification) = showNotification(notification)
        },
        logRepository = logs,
        notificationFactory = { update -> notification(update) },
        now = { now() },
    )

    private inner class FakeAppReleaseRepository(
        private var failures: Int = 0,
    ) : AppReleaseRepository {
        var calls = 0

        override fun getReleases(): IO<List<AppRelease>> = ioEffect {
            calls += 1
            if (failures > 0) {
                failures -= 1
                error("Network is unreachable")
            }
            listOf(release)
        }
    }
}
