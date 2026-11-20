package com.artemchep.keyguard.common.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A public release of the app.
 */
@Serializable
data class AppRelease(
    val version: AppSemVer,
    /** The git tag, e.g. `r20261116`. */
    val tag: String,
    /** The date in the tag. A rebuild of the same version has a later one. */
    val tagDate: LocalDate? = null,
    val publishedAt: Instant,
    /** The release page. */
    val url: String,
)
