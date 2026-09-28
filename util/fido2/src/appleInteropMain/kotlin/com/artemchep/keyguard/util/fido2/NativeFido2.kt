@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.fido2

import com.artemchep.keyguard.util.fido2.ffi.keyguard_fido2_abi_version
import com.artemchep.keyguard.util.fido2.ffi.keyguard_fido2_cancel
import com.artemchep.keyguard.util.fido2.ffi.keyguard_fido2_close
import com.artemchep.keyguard.util.fido2.ffi.keyguard_fido2_create
import com.artemchep.keyguard.util.fido2.ffi.keyguard_fido2_execute
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

internal actual object NativeFido2 {
    actual val isSupported: Boolean = true

    actual fun create(): Long {
        if (keyguard_fido2_abi_version().toInt() != FIDO2_ABI_VERSION) {
            throw Fido2Exception(Fido2Failure.PROTOCOL)
        }
        return keyguard_fido2_create().toLong()
    }

    actual fun execute(handle: Long, request: ByteArray): ByteArray {
        val result = ByteArray(FIDO2_MAX_RESPONSE)
        val size =
            request.usePinned { input ->
                result.usePinned { output ->
                    keyguard_fido2_execute(
                            handle.toULong(),
                            input.addressOf(0).reinterpret(),
                            request.size.toULong(),
                            output.addressOf(0).reinterpret(),
                            result.size.toULong(),
                        )
                        .toInt()
                }
            }
        if (size !in 1..result.size) throw Fido2Exception(Fido2Failure.PROTOCOL)
        return result.copyOf(size).also { result.fill(0) }
    }

    actual fun cancel(handle: Long) = keyguard_fido2_cancel(handle.toULong())

    actual fun close(handle: Long) = keyguard_fido2_close(handle.toULong())
}
