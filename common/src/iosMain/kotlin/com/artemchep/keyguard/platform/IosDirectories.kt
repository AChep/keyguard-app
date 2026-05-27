package com.artemchep.keyguard.platform

import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

fun iosKeyguardDataDirectory(): LocalPath {
    val base = NSSearchPathForDirectoriesInDomains(
        directory = NSApplicationSupportDirectory,
        domainMask = NSUserDomainMask,
        expandTilde = true,
    ).firstOrNull() as? String ?: error("Application Support directory is not available.")
    return LocalPath(base).resolve("Keyguard")
}
