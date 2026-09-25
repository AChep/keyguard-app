package com.artemchep.keyguard.platform

import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.util.io.toByteArray
import com.artemchep.keyguard.util.io.toNSData
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSURL
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
internal fun NSData.toSecurityScopedBookmarkToken(): String =
    Base64.Default.encode(toByteArray())

@OptIn(ExperimentalEncodingApi::class)
internal fun String.toSecurityScopedBookmarkDataOrNull(): NSData? = runCatching {
    Base64.Default.decode(this)
        .toNSData()
}.getOrNull()

/** Resolves this bookmark, or returns `null` when it is invalid. */
@OptIn(ExperimentalForeignApi::class)
internal fun FileAccessToken.resolveSecurityScopedUrlOrNull(
    isStale: CPointer<BooleanVar>? = null,
): NSURL? {
    val data = value.toSecurityScopedBookmarkDataOrNull()
        ?: return null
    return NSURL.URLByResolvingBookmarkData(
        bookmarkData = data,
        options = appleBookmarkResolutionOptions,
        relativeToURL = null,
        bookmarkDataIsStale = isStale,
        error = null,
    )
}

@OptIn(ExperimentalForeignApi::class)
internal inline fun <T> NSURL.withSecurityScopedAccess(
    block: () -> T,
): T {
    val didStartAccessing = startAccessingSecurityScopedResource()
    return try {
        block()
    } finally {
        if (didStartAccessing) {
            stopAccessingSecurityScopedResource()
        }
    }
}

internal expect val appleBookmarkCreationOptions: ULong
internal expect val appleBookmarkResolutionOptions: ULong
