package app.keemobile.kotpass.models

import kotlin.time.Instant
import kotlin.time.Clock

data class TimeData(
    val creationTime: Instant?,
    val lastAccessTime: Instant?,
    val lastModificationTime: Instant?,
    val locationChanged: Instant?,
    val expiryTime: Instant?,
    val expires: Boolean = false,
    val usageCount: Int = 0
) {
    companion object {
        fun create(now: Instant = Clock.System.now()) = TimeData(
            creationTime = now,
            lastAccessTime = now,
            lastModificationTime = now,
            locationChanged = now,
            expiryTime = null,
            expires = false,
            usageCount = 0
        )
    }
}
