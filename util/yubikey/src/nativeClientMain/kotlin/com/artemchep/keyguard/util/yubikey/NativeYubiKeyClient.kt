package com.artemchep.keyguard.util.yubikey

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException

/** USB OTP backend shared by JVM desktop and native macOS. iOS is unsupported. */
class NativeYubiKeyClient : YubiKeyClient {
    val isSupported: Boolean get() = NativeYubiKey.isSupported

    @Suppress("TooGenericExceptionCaught") // Complete the suspended caller for any native bridge exception.
    override suspend fun execute(operation: YubiKeyOperation): YubiKeyResult {
        if (!isSupported) throw YubiKeyException(YubiKeyFailure.UNSUPPORTED)
        // The native call blocks for up to 30 seconds while it waits for a touch.
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val handle = NativeYubiKey.create()
                if (handle == 0L) {
                    continuation.resumeWithException(YubiKeyException(YubiKeyFailure.BUSY))
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation { NativeYubiKey.cancel(handle) }
                val request = encodeYubiKeyRequest(operation)
                try {
                    val result = executeAndDecode(handle, operation, request)
                    continuation.resume(result) { _, value, _ ->
                        (value as? YubiKeyResult.Response)?.bytes?.fill(0)
                    }
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                } finally {
                    request.fill(0)
                    NativeYubiKey.close(handle)
                }
            }
        }
    }
}

private fun executeAndDecode(handle: Long, operation: YubiKeyOperation, request: ByteArray): YubiKeyResult {
    val response = NativeYubiKey.execute(handle, request)
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
