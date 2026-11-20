package com.artemchep.keyguard.common.service.release

import com.artemchep.keyguard.common.model.AppRelease
import com.artemchep.keyguard.common.model.AppSemVer
import com.artemchep.keyguard.common.service.serialization.createApplicationJson
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AppReleaseParserTest {
    private val gitHubReleasesJson = """
        [
          {
            "url": "https://api.github.com/repos/AChep/keyguard-app/releases/1",
            "html_url": "https://github.com/AChep/keyguard-app/releases/tag/r20261116",
            "tag_name": "r20261116",
            "name": "Release v3.8.2-20261116",
            "draft": false,
            "prerelease": false,
            "published_at": "2026-11-16T10:00:00Z",
            "assets": []
          },
          {
            "html_url": "https://github.com/AChep/keyguard-app/releases/tag/r20261024.1",
            "tag_name": "r20261024.1",
            "name": "Release v3.5.0-20261024.1",
            "draft": false,
            "prerelease": false,
            "published_at": "2026-10-24T18:30:00Z"
          },
          {
            "html_url": "https://github.com/AChep/keyguard-app/releases/tag/r20261120",
            "tag_name": "r20261120",
            "name": "Release v3.9.0-20261120",
            "draft": true,
            "prerelease": false,
            "published_at": null
          },
          {
            "html_url": "https://github.com/AChep/keyguard-app/releases/tag/r20261119",
            "tag_name": "r20261119",
            "name": "Release v3.9.0-20261119",
            "draft": false,
            "prerelease": true,
            "published_at": "2026-11-19T10:00:00Z"
          },
          {
            "html_url": "https://github.com/AChep/keyguard-app/releases/tag/nightly",
            "tag_name": "nightly",
            "name": "Nightly build",
            "draft": false,
            "prerelease": false,
            "published_at": "2026-11-18T10:00:00Z"
          },
          {
            "html_url": "https://github.com/AChep/keyguard-app/releases/tag/r20261117",
            "tag_name": "r20261117",
            "name": "Release v3.8.3-20261117",
            "draft": false,
            "prerelease": false,
            "published_at": "yesterday"
          },
          {
            "html_url": "http://example.com/r20261101",
            "tag_name": "r20261101",
            "name": "Release v3.7.0-20261101",
            "draft": false,
            "prerelease": false,
            "published_at": "2026-11-01T10:00:00Z"
          }
        ]
    """.trimIndent()

    private val expectedReleases = listOf(
        AppRelease(
            version = AppSemVer(3, 8, 2),
            tag = "r20261116",
            tagDate = LocalDate(2026, 11, 16),
            publishedAt = Instant.parse("2026-11-16T10:00:00Z"),
            url = "https://github.com/AChep/keyguard-app/releases/tag/r20261116",
        ),
        AppRelease(
            version = AppSemVer(3, 5, 0),
            tag = "r20261024.1",
            tagDate = LocalDate(2026, 10, 24),
            publishedAt = Instant.parse("2026-10-24T18:30:00Z"),
            url = "https://github.com/AChep/keyguard-app/releases/tag/r20261024.1",
        ),
        AppRelease(
            version = AppSemVer(3, 7, 0),
            tag = "r20261101",
            tagDate = LocalDate(2026, 11, 1),
            publishedAt = Instant.parse("2026-11-01T10:00:00Z"),
            // Only an HTTPS link is opened.
            url = "https://github.com/AChep/keyguard-app/releases",
        ),
    )

    @Test
    fun `parses the version and the date of a release name`() {
        assertEquals(
            AppReleaseName(
                version = AppSemVer(3, 8, 2),
                date = LocalDate(2026, 11, 16),
            ),
            parseAppReleaseName("Release v3.8.2-20261116"),
        )
        assertEquals(
            AppReleaseName(
                version = AppSemVer(3, 5, 0),
                date = LocalDate(2026, 10, 24),
            ),
            parseAppReleaseName("Release v3.5.0-20261024.1"),
        )
    }

    @Test
    fun `does not parse a malformed release name`() {
        listOf(
            "",
            "Release",
            "Release v3.8.2",
            "Release v3.8-20261116",
            "Release 3.8.2-20261116",
            "Release v3.8.2-2026111",
            "Release v99999999999.0.0-20261116",
        ).forEach { name ->
            assertNull(parseAppReleaseName(name), name)
        }
    }

    @Test
    fun `keeps a release name with an invalid date`() {
        val name = parseAppReleaseName("Release v3.8.2-20261399")
        assertEquals(AppSemVer(3, 8, 2), name?.version)
        assertNull(name?.date)
    }

    @Test
    fun `parses the date of a release tag`() {
        assertEquals(LocalDate(2026, 11, 16), parseAppReleaseTagDate("r20261116"))
        assertEquals(LocalDate(2026, 10, 24), parseAppReleaseTagDate("r20261024.1"))
        listOf(
            "",
            "v3.8.2",
            "r2026111",
            "r20261399",
            "r20261116-1",
            "release-r20261116",
        ).forEach { tag ->
            assertNull(parseAppReleaseTagDate(tag), tag)
        }
    }

    @Test
    fun `parses a build date`() {
        assertEquals(LocalDate(2026, 11, 16), parseAppBuildDate("20261116"))
        assertNull(parseAppBuildDate(""))
        assertNull(parseAppBuildDate("2026-11-16"))
        assertNull(parseAppBuildDate("unknown"))
    }

    @Test
    fun `parses only strict semantic versions`() {
        assertEquals(AppSemVer(3, 8, 2), AppSemVer.parseOrNull("3.8.2"))
        assertEquals(AppSemVer(10, 0, 12), AppSemVer.parseOrNull("10.0.12"))
        listOf(
            "",
            "3.8",
            "3.8.2.1",
            "3.8.2-beta",
            " 3.8.2",
            "v3.8.2",
            "99999999999.0.0",
        ).forEach { text ->
            assertNull(AppSemVer.parseOrNull(text), text)
        }
    }

    @Test
    fun `orders versions numerically`() {
        assertTrue(AppSemVer(3, 10, 0) > AppSemVer(3, 9, 9))
        assertTrue(AppSemVer(4, 0, 0) > AppSemVer(3, 99, 99))
        assertTrue(AppSemVer(3, 8, 2) < AppSemVer(3, 8, 10))
        assertEquals("3.10.0", AppSemVer(3, 10, 0).toString())
    }

    @Test
    fun `maps the GitHub releases and skips the unusable ones`() {
        val releases = parseGitHubReleases(
            json = createApplicationJson(),
            text = gitHubReleasesJson,
        )
        assertEquals(expectedReleases, releases)
    }

    @Test
    fun `maps an empty list`() {
        assertEquals(emptyList(), parseGitHubReleases(createApplicationJson(), "[]"))
    }
}
