package com.artemchep.keyguard.common.service.release

import com.artemchep.keyguard.URL_GITHUB
import com.artemchep.keyguard.common.model.AppRelease
import com.artemchep.keyguard.common.model.AppSemVer
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

// Releases are named `Release v3.8.2-20261116`, a rebuild
// adds a suffix: `Release v3.5.0-20261024.1`.
private val RELEASE_NAME_REGEX =
    "v([0-9]+\\.[0-9]+\\.[0-9]+)-([0-9]{8})(?:\\.[0-9]+)?".toRegex()

// Tags are `r20261116`, a rebuild adds a suffix: `r20261024.1`.
private val RELEASE_TAG_REGEX = "^r([0-9]{8})(?:\\.[0-9]+)?$".toRegex()

internal data class AppReleaseName(
    val version: AppSemVer,
    val date: LocalDate?,
)

/** Parses the version and the date out of a release name, or returns `null`. */
internal fun parseAppReleaseName(name: String): AppReleaseName? {
    val match = RELEASE_NAME_REGEX.find(name) ?: return null
    val (version, date) = match.destructured
    return AppSemVer.parseOrNull(version)?.let { semVer ->
        AppReleaseName(
            version = semVer,
            date = parseAppBuildDate(date),
        )
    }
}

/** Parses the date out of a release tag, or returns `null`. */
internal fun parseAppReleaseTagDate(tag: String): LocalDate? {
    val match = RELEASE_TAG_REGEX.matchEntire(tag) ?: return null
    return parseAppBuildDate(match.groupValues[1])
}

/** Parses a `yyyyMMdd` date, or returns `null`. */
fun parseAppBuildDate(text: String): LocalDate? = runCatching {
    LocalDate.parse(text, LocalDate.Formats.ISO_BASIC)
}.getOrNull()

@Serializable
private data class GitHubReleaseEntity(
    @SerialName("tag_name")
    val tagName: String? = null,
    @SerialName("name")
    val name: String? = null,
    @SerialName("draft")
    val draft: Boolean = false,
    @SerialName("prerelease")
    val prerelease: Boolean = false,
    @SerialName("published_at")
    val publishedAt: String? = null,
    @SerialName("html_url")
    val htmlUrl: String? = null,
)

/**
 * Parses the response of the GitHub list releases API. Skips the drafts,
 * the prereleases and the releases whose name does not parse.
 */
internal fun parseGitHubReleases(
    json: Json,
    text: String,
): List<AppRelease> = json
    .decodeFromString<List<GitHubReleaseEntity>>(text)
    .filterNot { entity ->
        entity.draft || entity.prerelease
    }
    .mapNotNull { entity ->
        entity.toDomainOrNull()
    }

private fun GitHubReleaseEntity.toDomainOrNull(): AppRelease? {
    val tag = tagName
        ?.takeIf { it.isNotBlank() }
    val parsedName = name
        ?.let(::parseAppReleaseName)
    val published = publishedAt
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (tag == null || parsedName == null || published == null) {
        return null
    }

    val url = htmlUrl
        ?.takeIf { it.startsWith("https://") }
        ?: "$URL_GITHUB/releases"
    return AppRelease(
        version = parsedName.version,
        tag = tag,
        tagDate = parseAppReleaseTagDate(tag) ?: parsedName.date,
        publishedAt = published,
        url = url,
    )
}
