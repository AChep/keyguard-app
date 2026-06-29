package com.artemchep.keyguard.common.service.file

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate

// NSDate is measured from 2001-01-01; this is the offset to the Unix epoch.
private const val IOS_REFERENCE_DATE_UNIX_OFFSET_SECONDS = 978_307_200.0

@OptIn(ExperimentalForeignApi::class)
internal actual fun fileLastModifiedMillis(path: String): Long? {
    val attributes = NSFileManager.defaultManager
        .attributesOfItemAtPath(path, error = null)
        ?: return null
    val modificationDate = attributes[NSFileModificationDate] as? NSDate
        ?: return null
    val unixSeconds = modificationDate.timeIntervalSinceReferenceDate +
        IOS_REFERENCE_DATE_UNIX_OFFSET_SECONDS
    return (unixSeconds * 1_000.0).toLong()
}
