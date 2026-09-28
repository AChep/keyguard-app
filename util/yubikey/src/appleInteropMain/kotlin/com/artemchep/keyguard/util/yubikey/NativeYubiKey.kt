@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.yubikey

import com.artemchep.keyguard.util.yubikey.ffi.keyguard_yubikey_abi_version
import com.artemchep.keyguard.util.yubikey.ffi.keyguard_yubikey_create
import com.artemchep.keyguard.util.yubikey.ffi.keyguard_yubikey_execute
import com.artemchep.keyguard.util.yubikey.ffi.keyguard_yubikey_cancel
import com.artemchep.keyguard.util.yubikey.ffi.keyguard_yubikey_close
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

internal actual object NativeYubiKey {
    actual val isSupported: Boolean = true
    actual fun create(): Long {
        if (keyguard_yubikey_abi_version().toInt() != YUBIKEY_ABI_VERSION) {
            throw YubiKeyException(YubiKeyFailure.PROTOCOL)
        }
        return keyguard_yubikey_create().toLong()
    }
    actual fun execute(handle: Long, request: ByteArray): ByteArray {
        val result = ByteArray(YUBIKEY_RESPONSE_LENGTH + 1)
        val size = request.usePinned { input ->
            result.usePinned { output ->
                keyguard_yubikey_execute(
                    handle.toULong(), input.addressOf(0).reinterpret(), request.size.toULong(),
                    output.addressOf(0).reinterpret(), result.size.toULong(),
                ).toInt()
            }
        }
        if (size !in 1..result.size) throw YubiKeyException(YubiKeyFailure.PROTOCOL)
        return result.copyOf(size).also { result.fill(0) }
    }
    actual fun cancel(handle: Long) = keyguard_yubikey_cancel(handle.toULong())
    actual fun close(handle: Long) = keyguard_yubikey_close(handle.toULong())
}
