package com.artemchep.keyguard.platform

import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryDestination
import com.artemchep.keyguard.util.io.resolve
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

fun iosKeyguardDataDirectory(): LocalPath = appleKeyguardDataDirectory()

fun iosKeyguardAtomicDataDirectory(): AtomicDirectoryDestination =
    appleKeyguardAtomicDataDirectory()

fun iosKeyguardCacheDirectory(): LocalPath {
    val base = NSSearchPathForDirectoriesInDomains(
        directory = NSCachesDirectory,
        domainMask = NSUserDomainMask,
        expandTilde = true,
    ).firstOrNull() as? String ?: error("Caches directory is not available.")
    return LocalPath(base).resolve("Keyguard")
}
