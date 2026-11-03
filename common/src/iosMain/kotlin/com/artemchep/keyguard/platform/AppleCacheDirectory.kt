package com.artemchep.keyguard.platform

import com.artemchep.keyguard.util.io.resolve
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

internal actual fun appleKeyguardCacheDirectory(): LocalPath {
    val base = NSSearchPathForDirectoriesInDomains(
        directory = NSCachesDirectory,
        domainMask = NSUserDomainMask,
        expandTilde = true,
    ).firstOrNull() as? String ?: error("Caches directory is not available.")
    return LocalPath(base).resolve("Keyguard")
}
