package com.artemchep.keyguard.util.io

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate

// NSDate is measured from 2001-01-01; this is the offset to the Unix epoch.
private const val APPLE_REFERENCE_DATE_UNIX_OFFSET_SECONDS = 978_307_200.0
private const val MILLISECONDS_PER_SECOND = 1_000.0

@OptIn(ExperimentalForeignApi::class)
actual fun LocalPath.lastModifiedMillis(): Long? {
    val modificationDate = NSFileManager.defaultManager
        .attributesOfItemAtPath(value, error = null)
        ?.get(NSFileModificationDate) as? NSDate
    return modificationDate?.let { date ->
        val unixSeconds = date.timeIntervalSinceReferenceDate +
            APPLE_REFERENCE_DATE_UNIX_OFFSET_SECONDS
        (unixSeconds * MILLISECONDS_PER_SECOND).toLong()
    }
}
