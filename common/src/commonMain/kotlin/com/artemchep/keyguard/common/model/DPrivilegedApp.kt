package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.util.PROTOCOL_ANDROID_APP
import com.artemchep.keyguard.common.util.REGEX_ANDROID_APP
import com.artemchep.keyguard.common.util.normalizeSha256FingerprintOrNull
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import kotlin.time.Instant

data class DPrivilegedApp(
    val id: String? = null,
    val name: String?,
    val packageName: String,
    val certFingerprintSha256: String,
    val createdDate: Instant?,
    val source: Source,
) : Comparable<DPrivilegedApp> {
    enum class Source {
        USER,
        APP,
    }

    override fun compareTo(other: DPrivilegedApp): Int {
        return AlphabeticalSort.compareStr(packageName, other.packageName)
    }
}

private const val PACKAGE_NAME_MAX_LENGTH = 255

/**
 * Whether the [packageName] is an Android package name, and the [certFingerprintSha256] is
 * a fingerprint in the form the list keeps, which is the form Android matches an app by:
 * 32 uppercase hex bytes, joined by colons.
 */
internal fun isPrivilegedAppValid(packageName: String, certFingerprintSha256: String): Boolean =
    packageName.length <= PACKAGE_NAME_MAX_LENGTH &&
        REGEX_ANDROID_APP.matches(PROTOCOL_ANDROID_APP + packageName) &&
        certFingerprintSha256.normalizeSha256FingerprintOrNull() == certFingerprintSha256
