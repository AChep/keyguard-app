package com.artemchep.keyguard.util.io.bridge

import com.artemchep.keyguard.util.io.FileSystemOperationException

/**
 * Eagerly verifies that the native I/O core is loadable and speaks the
 * expected ABI.
 *
 * Applications should call this at startup so a packaging or extraction
 * problem surfaces as an explicit "storage engine unavailable" condition
 * instead of a failed save later.
 *
 * @throws FileSystemOperationException when the native library is missing or
 * incompatible.
 */
fun ensureNativeIoAvailable() {
    // Any call verifies loading plus the ABI handshake. An empty role mask is
    // a specified complete no-op before path validation.
    NativeIo.sweepOrphans(
        directory = "",
        olderThanMs = Long.MAX_VALUE,
        roleMask = 0,
    )
}
