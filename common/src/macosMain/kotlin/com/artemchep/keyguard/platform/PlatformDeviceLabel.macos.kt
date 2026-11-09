package com.artemchep.keyguard.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.CFBridgingRelease
import platform.SystemConfiguration.SCDynamicStoreCopyComputerName
import platform.posix.gethostname

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun platformDeviceLabel(context: LeContext): String? = withContext(Dispatchers.IO) {
    firstDeviceLabel(
        { SCDynamicStoreCopyComputerName(null, null)?.let { CFBridgingRelease(it) as? String } },
        {
            memScoped {
                // A DNS hostname fits in 255 bytes. The extra zero also bounds toKString on truncation.
                val buffer = allocArray<ByteVar>(HOSTNAME_BUFFER_SIZE) { value = 0 }
                if (gethostname(buffer, (HOSTNAME_BUFFER_SIZE - 1).convert()) == 0) buffer.toKString() else null
            }
        },
    )
}

private const val HOSTNAME_BUFFER_SIZE = 256
