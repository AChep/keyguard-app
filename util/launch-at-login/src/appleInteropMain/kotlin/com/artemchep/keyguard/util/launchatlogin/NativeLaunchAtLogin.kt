@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.launchatlogin

import com.artemchep.keyguard.util.ffi.encodeToStrictUtf8OrNull
import com.artemchep.keyguard.util.ffi.withNativePointer
import com.artemchep.keyguard.util.launch_at_login.ffi.keyguard_launch_at_login_abi_version
import com.artemchep.keyguard.util.launch_at_login.ffi.keyguard_launch_at_login_execute
import com.artemchep.keyguard.util.launch_at_login.ffi.keyguard_launch_at_login_last_error

private val INVALID_UTF8 = byteArrayOf(0xFF.toByte())

internal actual object NativeLaunchAtLogin {
    actual fun execute(operation: Int, configuration: String): Int {
        if (keyguard_launch_at_login_abi_version() != 1u) {
            throw LaunchAtLoginException(LaunchAtLoginError.UNAVAILABLE, "Unsupported launch-at-login ABI")
        }
        // An unpaired surrogate goes through the native UTF-8 rejection, so it gets the
        // invalid-argument code and diagnostic that the JNI bridge returns for the same string.
        val bytes = configuration.encodeToStrictUtf8OrNull() ?: INVALID_UTF8
        require(bytes.size <= LAUNCH_REQUEST_MAX_LENGTH)
        return bytes.withNativePointer { pointer, length ->
            keyguard_launch_at_login_execute(operation, pointer, length)
        }
    }

    actual fun lastError(): String? {
        val size = keyguard_launch_at_login_last_error(null, 0uL)
        if (size == 0uL || size > LAUNCH_REQUEST_MAX_LENGTH.toULong()) return null
        val bytes = ByteArray(size.toInt())
        val copied = bytes.withNativePointer { pointer, capacity ->
            keyguard_launch_at_login_last_error(pointer, capacity)
        }
        return if (copied == size) bytes.decodeToString() else null
    }
}
