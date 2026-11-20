package com.artemchep.keyguard.common.model

import kotlinx.serialization.Serializable

/**
 * A `MAJOR.MINOR.PATCH` version of the app.
 */
@Serializable
data class AppSemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AppSemVer> {
    companion object {
        private const val PART_COUNT = 3

        private val REGEX = "^([0-9]+)\\.([0-9]+)\\.([0-9]+)$".toRegex()

        private val COMPARATOR = compareBy<AppSemVer>(
            { it.major },
            { it.minor },
            { it.patch },
        )

        /** Parses a strict `MAJOR.MINOR.PATCH` version, or returns `null`. */
        fun parseOrNull(text: String): AppSemVer? = REGEX.matchEntire(text)
            ?.groupValues
            ?.drop(1)
            // A part that does not fit an Int is dropped.
            ?.mapNotNull(String::toIntOrNull)
            ?.takeIf { it.size == PART_COUNT }
            ?.let { (major, minor, patch) ->
                AppSemVer(
                    major = major,
                    minor = minor,
                    patch = patch,
                )
            }
    }

    override fun compareTo(other: AppSemVer): Int = COMPARATOR.compare(this, other)

    override fun toString(): String = "$major.$minor.$patch"
}
