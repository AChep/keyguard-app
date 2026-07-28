package com.artemchep.keyguard.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.posix.memcpy
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
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) {
        return ByteArray(0)
    }

    val source = checkNotNull(bytes) {
        "NSData bytes pointer is null."
    }
    return ByteArray(size).also { output ->
        output.usePinned { outputPinned ->
            memcpy(
                outputPinned.addressOf(0),
                source,
                size.convert(),
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    val data = if (isEmpty()) {
        CFDataCreate(
            allocator = null,
            bytes = null,
            length = 0.convert(),
        )
    } else {
        usePinned { pinned ->
            CFDataCreate(
                allocator = null,
                bytes = pinned.addressOf(0).reinterpret(),
                length = size.convert(),
            )
        }
    }
    checkNotNull(data) {
        "Could not allocate bookmark data."
    }
    return CFBridgingRelease(data) as NSData
}
