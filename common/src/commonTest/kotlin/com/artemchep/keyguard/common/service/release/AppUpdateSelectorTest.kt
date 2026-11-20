package com.artemchep.keyguard.common.service.release

import com.artemchep.keyguard.common.model.AppRelease
import com.artemchep.keyguard.common.model.AppSemVer
import com.artemchep.keyguard.common.model.AppUpdateLevel
import com.artemchep.keyguard.common.model.DAppUpdate
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class AppUpdateSelectorTest {
    private val now = Instant.parse("2026-11-20T12:00:00Z")

    private val installedVersion = AppSemVer(3, 8, 2)

    private val installedBuildDate = LocalDate(2026, 11, 16)

    @Test
    fun `off never selects a release`() {
        val releases = listOf(release("4.0.0", "20261101"))
        assertNull(select(releases, level = AppUpdateLevel.OFF))
    }

    @Test
    fun `major selects only a new major version`() {
        assertNull(select(listOf(release("3.9.0", "20261101")), level = AppUpdateLevel.MAJOR))
        assertEquals(
            update("4.0.0", "20261101"),
            select(listOf(release("4.0.0", "20261101")), level = AppUpdateLevel.MAJOR),
        )
    }

    @Test
    fun `minor selects a new major or minor version`() {
        assertNull(select(listOf(release("3.8.5", "20261101")), level = AppUpdateLevel.MINOR))
        assertEquals(
            update("3.9.0", "20261101"),
            select(listOf(release("3.9.0", "20261101")), level = AppUpdateLevel.MINOR),
        )
        assertEquals(
            update("4.0.0", "20261101"),
            select(listOf(release("4.0.0", "20261101")), level = AppUpdateLevel.MINOR),
        )
    }

    @Test
    fun `all selects a new patch version`() {
        assertEquals(
            update("3.8.3", "20261101"),
            select(listOf(release("3.8.3", "20261101")), level = AppUpdateLevel.ALL),
        )
    }

    @Test
    fun `all selects a later rebuild of the installed version`() {
        val rebuild = listOf(release("3.8.2", "20261117"))
        assertEquals(update("3.8.2", "20261117"), select(rebuild, level = AppUpdateLevel.ALL))
        assertNull(select(rebuild, level = AppUpdateLevel.MINOR))
        assertNull(select(rebuild, level = AppUpdateLevel.MAJOR))
        // The installed build itself, or an older one.
        assertNull(select(listOf(release("3.8.2", "20261116")), level = AppUpdateLevel.ALL))
        assertNull(select(listOf(release("3.8.2", "20261110")), level = AppUpdateLevel.ALL))
        // An unknown build date can not tell a rebuild apart.
        assertNull(select(rebuild, level = AppUpdateLevel.ALL, installedBuildDate = null))
    }

    @Test
    fun `does not select an older version`() {
        val releases = listOf(
            release("3.7.0", "20261001"),
            release("3.8.1", "20261110"),
        )
        assertNull(select(releases, level = AppUpdateLevel.ALL))
    }

    @Test
    fun `selects a release that is public for exactly the delay`() {
        val releases = listOf(release("3.9.0", "20261113", publishedAt = now - 7.days))
        assertEquals(update("3.9.0", "20261113"), select(releases, delay = 7.days))

        val fresh = listOf(release("3.9.0", "20261113", publishedAt = now - 7.days + 1.milliseconds))
        assertNull(select(fresh, delay = 7.days))
        assertEquals(update("3.9.0", "20261113"), select(fresh, delay = Duration.ZERO))
    }

    @Test
    fun `shows the newest release once an older one is past the delay`() {
        val releases = listOf(
            release("3.10.0", "20261119", publishedAt = now - 1.days),
            release("3.9.0", "20261101", publishedAt = now - 19.days),
            release("3.8.2", "20261116", publishedAt = now - 4.days),
        )
        assertEquals(
            update("3.10.0", "20261119").copy(eligibleTag = "r20261101"),
            select(releases, delay = 7.days),
        )
        assertEquals(update("3.10.0", "20261119"), select(releases, delay = 1.days))
    }

    @Test
    fun `a fresh release alone does not pass the delay`() {
        val releases = listOf(
            release("3.10.0", "20261119", publishedAt = now - 1.days),
            // Past the delay, but not newer than the installed version.
            release("3.8.1", "20261110", publishedAt = now - 10.days),
        )
        assertNull(select(releases, delay = 7.days))
    }

    @Test
    fun `picks the newest version regardless of the order`() {
        val releases = listOf(
            release("3.9.0", "20261101"),
            release("3.9.1", "20261105"),
            // A same-day rebuild: only the publish time tells it apart.
            release("3.9.1", "20261105.1", publishedAt = now - 29.days),
            release("3.8.3", "20261110"),
        )
        assertEquals(
            update("3.9.1", "20261105.1"),
            select(releases.shuffled(), level = AppUpdateLevel.ALL),
        )
    }

    @Test
    fun `next change is when the next release crosses the delay`() {
        val releases = listOf(
            release("3.9.0", "20261101", publishedAt = now - 19.days),
            release("3.10.0", "20261119", publishedAt = now - 1.days),
            release("3.10.1", "20261120", publishedAt = now),
        )
        assertEquals(now + 6.days, nextAppUpdateChangeAt(releases, delay = 7.days, now = now))
        assertNull(nextAppUpdateChangeAt(releases, delay = Duration.ZERO, now = now))
        assertNull(nextAppUpdateChangeAt(emptyList(), delay = 7.days, now = now))
    }

    private fun select(
        releases: List<AppRelease>,
        level: AppUpdateLevel = AppUpdateLevel.ALL,
        delay: Duration = Duration.ZERO,
        installedBuildDate: LocalDate? = this.installedBuildDate,
    ) = selectAppUpdate(
        releases = releases,
        installedVersion = installedVersion,
        installedBuildDate = installedBuildDate,
        level = level,
        delay = delay,
        now = now,
    )

    private fun release(
        version: String,
        tag: String,
        tagDate: LocalDate? = parseAppReleaseTagDate("r$tag"),
        publishedAt: Instant = now - 30.days,
    ) = AppRelease(
        version = AppSemVer.parseOrNull(version)!!,
        tag = "r$tag",
        tagDate = tagDate,
        publishedAt = publishedAt,
        url = "https://github.com/AChep/keyguard-app/releases/tag/r$tag",
    )

    private fun update(
        version: String,
        tag: String,
    ) = DAppUpdate(
        version = version,
        url = "https://github.com/AChep/keyguard-app/releases/tag/r$tag",
        eligibleTag = "r$tag",
    )
}
