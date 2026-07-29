@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.io

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * Copies this data into a fresh [ByteArray].
 *
 * The copy is independent of the receiver's lifetime; callers holding
 * secrets are responsible for zero-filling the array when done.
 */
fun NSData.toByteArray(): ByteArray {
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

/**
 * Copies this array into a fresh immutable [NSData].
 */
fun ByteArray.toNSData(): NSData {
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
        "Could not allocate NSData."
    }
    return CFBridgingRelease(data) as NSData
}
