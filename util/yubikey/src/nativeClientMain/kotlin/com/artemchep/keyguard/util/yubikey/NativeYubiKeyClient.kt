package com.artemchep.keyguard.util.yubikey

import com.artemchep.keyguard.util.ffi.runNativeOperation

/** USB OTP backend shared by JVM desktop and native macOS. iOS is unsupported. */
class NativeYubiKeyClient : YubiKeyClient {
    val isSupported: Boolean get() = NativeYubiKey.isSupported

    override suspend fun execute(operation: YubiKeyOperation): YubiKeyResult {
        if (!isSupported) throw YubiKeyException(YubiKeyFailure.UNSUPPORTED)
        // The native call blocks for up to 30 seconds while it waits for a touch.
        return runNativeOperation(
            create = NativeYubiKey::create,
            cancel = NativeYubiKey::cancel,
            close = NativeYubiKey::close,
            busy = { YubiKeyException(YubiKeyFailure.BUSY) },
            clear = { result -> (result as? YubiKeyResult.Response)?.bytes?.fill(0) },
        ) { handle -> executeAndDecode(handle, operation) }
    }
}

private fun executeAndDecode(handle: Long, operation: YubiKeyOperation): YubiKeyResult {
    val request = encodeYubiKeyRequest(operation)
    val response =
        try {
            NativeYubiKey.execute(handle, request)
        } finally {
            request.fill(0)
        }
    try {
        return decodeYubiKeyResponse(operation, response)
    } finally {
        response.fill(0)
    }
}

internal expect object NativeYubiKey {
    val isSupported: Boolean
    fun create(): Long
    fun execute(handle: Long, request: ByteArray): ByteArray
    fun cancel(handle: Long)
    fun close(handle: Long)
}
