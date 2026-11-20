package com.artemchep.keyguard.common.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * The releases the last check found. Changing the update settings
 * picks from this list again, without a new request.
 */
@Serializable
data class AppReleaseCache(
    val lastCheckedAt: Instant,
    val releases: List<AppRelease> = emptyList(),
)
