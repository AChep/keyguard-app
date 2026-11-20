package com.artemchep.keyguard.common.service.release

import com.artemchep.keyguard.common.model.AppRelease
import com.artemchep.keyguard.common.model.AppSemVer
import com.artemchep.keyguard.common.model.AppUpdateLevel
import com.artemchep.keyguard.common.model.DAppUpdate
import kotlinx.datetime.LocalDate
import kotlin.time.Duration
import kotlin.time.Instant

private val RELEASE_COMPARATOR = compareBy<AppRelease>(
    { it.version },
    { it.tagDate },
    { it.publishedAt },
)

/**
 * Picks the release to tell the user about, or returns `null`.
 *
 * The [delay] decides when to tell: only the newest release that is
 * public for at least [delay] is checked. Releases are monotonic, so
 * if it is not newer than the installed version at the [level], no
 * older one is either. Once it is, the result is the newest release,
 * since nobody should install an older one.
 *
 * The result links to the release page of the newest release.
 *
 * @param installedBuildDate the build date of the installed app; a
 * release of the same version with a later tag date is a rebuild.
 */
fun selectAppUpdate(
    releases: List<AppRelease>,
    installedVersion: AppSemVer,
    installedBuildDate: LocalDate?,
    level: AppUpdateLevel,
    delay: Duration,
    now: Instant,
): DAppUpdate? {
    val minAge = delay.coerceAtLeast(Duration.ZERO)
    val eligible = releases
        .filter { release ->
            release.publishedAt + minAge <= now
        }
        .maxWithOrNull(RELEASE_COMPARATOR)
        ?.takeIf { release ->
            release.isNewerThan(
                installedVersion = installedVersion,
                installedBuildDate = installedBuildDate,
                level = level,
            )
        }
        ?: return null
    val newest = releases.maxWith(RELEASE_COMPARATOR)
    return DAppUpdate(
        version = newest.version.toString(),
        url = newest.url,
        eligibleTag = eligible.tag,
    )
}

/**
 * Returns the next instant after [now] at which one of the [releases]
 * becomes public for at least [delay], or `null` if there is none.
 */
fun nextAppUpdateChangeAt(
    releases: List<AppRelease>,
    delay: Duration,
    now: Instant,
): Instant? {
    val minAge = delay.coerceAtLeast(Duration.ZERO)
    return releases
        .map { release ->
            release.publishedAt + minAge
        }
        .filter { it > now }
        .minOrNull()
}

private fun AppRelease.isNewerThan(
    installedVersion: AppSemVer,
    installedBuildDate: LocalDate?,
    level: AppUpdateLevel,
): Boolean = when (level) {
    AppUpdateLevel.OFF -> false
    AppUpdateLevel.MAJOR -> version.major > installedVersion.major
    AppUpdateLevel.MINOR -> compareValuesBy(
        version,
        installedVersion,
        { it.major },
        { it.minor },
    ) > 0

    AppUpdateLevel.ALL -> when {
        version > installedVersion -> true
        version < installedVersion -> false
        // A rebuild of the installed version.
        else -> tagDate != null &&
                installedBuildDate != null &&
                tagDate > installedBuildDate
    }
}
