package com.artemchep.keyguard.common.service.keepass.storage

import kotlin.time.Instant

data class KeePassDatabaseMetadata(
    /**
     * Storage-provided entity tag for the database object, used as the preferred
     * change token when the backing store supports it, such as WebDAV.
     */
    val etag: String?,
    val lastModified: Instant?,
    val size: Long?,
) {
    fun isComparableWith(
        other: KeePassDatabaseMetadata,
    ): Boolean = when {
        etag != null && other.etag != null -> true
        lastModified != null && other.lastModified != null &&
                size != null && other.size != null -> true

        else -> false
    }

    fun differsFrom(
        other: KeePassDatabaseMetadata,
    ): Boolean = when {
        etag != null && other.etag != null -> etag != other.etag
        lastModified != null && other.lastModified != null &&
                size != null && other.size != null ->
            lastModified != other.lastModified || size != other.size

        else -> false
    }
}
