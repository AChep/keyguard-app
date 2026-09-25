package com.artemchep.keyguard.platform

import com.artemchep.keyguard.util.io.toByteArray
import com.artemchep.keyguard.util.io.toNSData
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
