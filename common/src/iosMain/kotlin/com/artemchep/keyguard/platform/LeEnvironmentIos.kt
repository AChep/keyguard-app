package com.artemchep.keyguard.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv as posixGetenv

actual object LeEnvironment {
    @OptIn(ExperimentalForeignApi::class)
    actual fun getenv(name: String): String? =
        posixGetenv(name)
            ?.toKString()
}
