package com.artemchep.desktop

import com.artemchep.jna.findLibBinaryPathOrNull
import com.artemchep.keyguard.util.ffi.JniLibrary
import com.sun.jna.Platform

private val windowsPackageIdentityLibrary = JniLibrary.fromAbsolutePath(
    path = { findLibBinaryPathOrNull()?.toString() },
    unavailable = { IllegalStateException("The Windows package identity bridge is unavailable", it) },
)

/** The package family of the installed Windows launcher, if it runs from a package. */
public fun currentWindowsPackageFamilyName(): String? {
    if (!Platform.isWindows()) return null
    return runCatching {
        windowsPackageIdentityLibrary.ensureLoaded()
        WindowsPackageIdentityJni.familyName()
    }.getOrNull()
}

internal object WindowsPackageIdentityJni {
    external fun familyName(): String?
}
