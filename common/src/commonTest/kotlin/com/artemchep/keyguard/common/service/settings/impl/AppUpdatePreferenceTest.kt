package com.artemchep.keyguard.common.service.settings.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AppRelease
import com.artemchep.keyguard.common.model.AppReleaseCache
import com.artemchep.keyguard.common.model.AppSemVer
import com.artemchep.keyguard.common.model.AppUpdateLevel
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class AppUpdatePreferenceTest {
    private val cache = AppReleaseCache(
        lastCheckedAt = Instant.parse("2026-11-20T12:00:00Z"),
        releases = listOf(
            AppRelease(
                version = AppSemVer(3, 8, 2),
                tag = "r20261116",
                tagDate = LocalDate(2026, 11, 16),
                publishedAt = Instant.parse("2026-11-16T10:00:00Z"),
                url = "https://github.com/AChep/keyguard-app/releases/tag/r20261116",
            ),
        ),
    )

    @Test
    fun `defaults to all releases after a week`() = runTest {
        val repository = createRepository()
        assertEquals(AppUpdateLevel.ALL, repository.getAppUpdateLevel().first())
        assertEquals(7.days, repository.getAppUpdateDelay().first())
        assertNull(repository.getAppUpdateCache().first())
        assertNull(repository.getAppUpdateNotifiedTag().first())
    }

    @Test
    fun `round trips every value across a reload`() = runTest {
        val store = JsonKeyValueStore()
        val repository = createRepository(store)
        repository.setAppUpdateLevel(AppUpdateLevel.MAJOR).bind()
        repository.setAppUpdateDelay(Duration.ZERO).bind()
        repository.setAppUpdateCache(cache).bind()
        repository.setAppUpdateNotifiedTag("r20261116").bind()

        val reloaded = createRepository(store)
        assertEquals(AppUpdateLevel.MAJOR, reloaded.getAppUpdateLevel().first())
        assertEquals(Duration.ZERO, reloaded.getAppUpdateDelay().first())
        assertEquals(cache, reloaded.getAppUpdateCache().first())
        assertEquals("r20261116", reloaded.getAppUpdateNotifiedTag().first())

        reloaded.setAppUpdateCache(null).bind()
        reloaded.setAppUpdateNotifiedTag(null).bind()
        assertNull(reloaded.getAppUpdateCache().first())
        assertNull(reloaded.getAppUpdateNotifiedTag().first())
    }

    @Test
    fun `falls back to the defaults for unknown values`() = runTest {
        val repository = createRepository()
        repository.restore(
            mapOf(
                "app_update.level" to "sometimes",
                "app_update.delay" to -5L,
            ),
        ).bind()
        assertEquals(AppUpdateLevel.ALL, repository.getAppUpdateLevel().first())
        assertEquals(Duration.ZERO, repository.getAppUpdateDelay().first())
    }

    @Test
    fun `backs up the settings but not the cached state`() = runTest {
        val repository = createRepository()
        repository.setAppUpdateLevel(AppUpdateLevel.MINOR).bind()
        repository.setAppUpdateDelay(14.days).bind()
        repository.setAppUpdateCache(cache).bind()
        repository.setAppUpdateNotifiedTag("r20261116").bind()

        val exported = repository.getPrefs(includeInternalPrefs = false).map { it.key }
        assertTrue("app_update.level" in exported)
        assertTrue("app_update.delay" in exported)
        assertFalse("app_update.cache" in exported)
        assertFalse("app_update.notified_tag" in exported)
        val all = repository.getPrefs(includeInternalPrefs = true).map { it.key }
        assertTrue("app_update.cache" in all)
        assertTrue("app_update.notified_tag" in all)

        val restored = createRepository()
        restored.restore(repository.backup().bind()).bind()
        assertEquals(AppUpdateLevel.MINOR, restored.getAppUpdateLevel().first())
        assertEquals(14.days, restored.getAppUpdateDelay().first())
        assertNull(restored.getAppUpdateCache().first())
        assertNull(restored.getAppUpdateNotifiedTag().first())
    }

    private fun createRepository(store: JsonKeyValueStore = JsonKeyValueStore()) = SettingsRepositoryImpl(
        store = store,
        json = Json,
        base64Service = Base64ServiceImpl(),
    )
}
