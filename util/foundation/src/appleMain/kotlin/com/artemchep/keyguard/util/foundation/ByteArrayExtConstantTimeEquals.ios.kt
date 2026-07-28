package com.artemchep.keyguard.util.foundation

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.timingsafe_bcmp

@OptIn(ExperimentalForeignApi::class)
actual fun ByteArray.constantTimeEquals(
    other: ByteArray,
): Boolean {
    // Array length is not secret for MAC/tag comparison (it is fixed and public), so a
    // length check here leaks nothing. This matches the JVM backend (MessageDigest.isEqual),
    // which likewise returns early on a length mismatch.
    if (size != other.size) {
        return false
    }
    if (isEmpty()) {
        return true
    }
    // Delegate the byte comparison to libc timingsafe_bcmp. Unlike a hand-rolled loop —
    // whose data-independent timing the Kotlin/Native LLVM backend does not guarantee to
    // preserve — timingsafe_bcmp is documented by Darwin (available since iOS 10.1) to
    // compare in time independent of the byte values. It returns 0 iff the buffers match.
    return usePinned { a ->
        other.usePinned { b ->
            timingsafe_bcmp(a.addressOf(0), b.addressOf(0), size.convert()) == 0
        }
    }
}
