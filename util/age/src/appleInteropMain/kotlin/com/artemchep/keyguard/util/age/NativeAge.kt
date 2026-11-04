@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.age

import com.artemchep.keyguard.util.age.ffi.keyguard_age_abi_version
import com.artemchep.keyguard.util.age.ffi.keyguard_age_close
import com.artemchep.keyguard.util.age.ffi.keyguard_age_create
import com.artemchep.keyguard.util.age.ffi.keyguard_age_execute
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

internal actual object NativeAge {

    actual fun create(): Long {
        if (keyguard_age_abi_version().toInt() != AGE_ABI_VERSION) {
            throw AgeException(AgeFailure.Corrupt)
        }
        return keyguard_age_create().toLong()
    }

    actual fun execute(handle: Long, request: ByteArray): ByteArray {
        val result = ByteArray(AGE_MAX_RESPONSE)
        var written = result.size
        try {
            val size = request.usePinned { input ->
                result.usePinned { output ->
                    keyguard_age_execute(
                        handle.toULong(),
                        input.addressOf(0).reinterpret(),
                        request.size.toULong(),
                        output.addressOf(0).reinterpret(),
                        result.size.toULong(),
                    ).toInt()
                }
            }
            if (size !in 1..result.size) throw AgeException(AgeFailure.Corrupt)
            written = size
            return result.copyOf(size)
        } finally {
            // Native code writes only the reported prefix.
            result.fill(0, 0, written)
        }
    }


    actual fun close(handle: Long) = keyguard_age_close(handle.toULong())
}
