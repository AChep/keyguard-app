package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.AppRelease
import com.artemchep.keyguard.common.model.AppReleaseCache
import com.artemchep.keyguard.common.model.AppSemVer
import com.artemchep.keyguard.common.model.AppUpdateLevel
import com.artemchep.keyguard.common.model.DAppUpdate
import com.artemchep.keyguard.common.service.flavor.AppDistribution
import com.artemchep.keyguard.common.service.release.FakeAppUpdateSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GetAppUpdateImplTest {
    private val start = Instant.parse("2026-11-20T12:00:00Z")

    private val release = AppRelease(
        version = AppSemVer(3, 9, 0),
        tag = "r20261119",
        tagDate = LocalDate(2026, 11, 19),
        publishedAt = start - 1.days,
        url = "https://github.com/AChep/keyguard-app/releases/tag/r20261119",
    )

    private val update = DAppUpdate(
        version = "3.9.0",
        url = "https://github.com/AChep/keyguard-app/releases/tag/r20261119",
        eligibleTag = "r20261119",
    )

    @Test
    fun `emits a release once it is public for long enough`() = runTest {
        val settings = FakeAppUpdateSettings()
        settings.cache.value = AppReleaseCache(lastCheckedAt = start, releases = listOf(release))
        settings.delay.value = 7.days
        val values = collect(createUseCase(settings))

        runCurrent()
        assertEquals(listOf<DAppUpdate?>(null), values)

        advanceTimeBy(6.days - 1.milliseconds)
        runCurrent()
        assertEquals(listOf<DAppUpdate?>(null), values)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(listOf(null, update), values)
    }

    @Test
    fun `picks again when the settings change`() = runTest {
        val settings = FakeAppUpdateSettings()
        settings.cache.value = AppReleaseCache(lastCheckedAt = start, releases = listOf(release))
        settings.delay.value = 7.days
        val values = collect(createUseCase(settings))
        runCurrent()

        settings.delay.value = Duration.ZERO
        runCurrent()
        assertEquals(listOf(null, update), values)

        settings.level.value = AppUpdateLevel.MAJOR
        runCurrent()
        assertEquals(listOf(null, update, null), values)

        settings.cache.value = null
        runCurrent()
        settings.level.value = AppUpdateLevel.ALL
        runCurrent()
        assertEquals(listOf(null, update, null), values)
    }

    @Test
    fun `store builds link to the store listing`() = runTest {
        val settings = FakeAppUpdateSettings()
        settings.cache.value = AppReleaseCache(lastCheckedAt = start, releases = listOf(release))
        settings.delay.value = Duration.ZERO
        val storeUrl = "https://play.google.com/store/apps/details?id=com.artemchep.keyguard"
        val store = AppDistribution(isStore = true, storeUrl = storeUrl)
        val listed = collect(createUseCase(settings, distribution = store))
        val unlisted = collect(createUseCase(settings, distribution = AppDistribution(isStore = true)))

        runCurrent()
        assertEquals(listOf<DAppUpdate?>(update.copy(url = storeUrl)), listed)
        assertEquals(listOf<DAppUpdate?>(update.copy(url = null)), unlisted)
    }

    @Test
    fun `no link without a browser`() = runTest {
        val settings = FakeAppUpdateSettings()
        settings.cache.value = AppReleaseCache(lastCheckedAt = start, releases = listOf(release))
        settings.delay.value = Duration.ZERO
        val storeUrl = "https://play.google.com/store/apps/details?id=com.artemchep.keyguard"
        val store = AppDistribution(isStore = true, storeUrl = storeUrl)
        val github = collect(createUseCase(settings, hasBrowser = false))
        val listed = collect(createUseCase(settings, distribution = store, hasBrowser = false))

        runCurrent()
        assertEquals(listOf<DAppUpdate?>(update.copy(url = null)), github)
        assertEquals(listOf<DAppUpdate?>(update.copy(url = null)), listed)
    }

    @Test
    fun `an unknown installed version never has an update`() = runTest {
        val settings = FakeAppUpdateSettings()
        settings.cache.value = AppReleaseCache(lastCheckedAt = start, releases = listOf(release))
        settings.delay.value = Duration.ZERO
        val values = collect(createUseCase(settings, versionName = "3.8.2-dev"))

        runCurrent()
        assertEquals(listOf<DAppUpdate?>(null), values)
    }

    @Test
    fun `an unknown build date still finds a newer version`() = runTest {
        val settings = FakeAppUpdateSettings()
        settings.cache.value = AppReleaseCache(lastCheckedAt = start, releases = listOf(release))
        settings.delay.value = Duration.ZERO
        val values = collect(createUseCase(settings, buildDate = "unknown"))

        runCurrent()
        assertEquals(listOf<DAppUpdate?>(update), values)
    }

    private fun TestScope.collect(
        useCase: GetAppUpdateImpl,
    ): List<DAppUpdate?> {
        val values = mutableListOf<DAppUpdate?>()
        backgroundScope.launch {
            useCase().toList(values)
        }
        return values
    }

    private fun TestScope.createUseCase(
        settings: FakeAppUpdateSettings,
        distribution: AppDistribution = AppDistribution(isStore = false),
        versionName: String = "3.8.2",
        buildDate: String = "20261116",
        hasBrowser: Boolean = true,
    ) = GetAppUpdateImpl(
        settingsReadRepository = settings,
        getAppUpdateLevel = GetAppUpdateLevelImpl(settings),
        getAppUpdateDelay = GetAppUpdateDelayImpl(settings),
        appDistribution = distribution,
        versionName = versionName,
        buildDate = buildDate,
        hasBrowser = hasBrowser,
        scope = backgroundScope,
        now = { start + testScheduler.currentTime.milliseconds },
    )
}
