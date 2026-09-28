package com.artemchep.keyguard.util.fido2

import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Native USB client for desktop; device handles exist only during an operation. */
class NativeFido2Client {
    val isSupported: Boolean
        get() = NativeFido2.isSupported

    /**
     * Returns a credential ID for registration, or a 32-byte PRF result for derivation. The caller
     * owns the result and must clear secret bytes after use.
     */
    @Suppress("TooGenericExceptionCaught") // Finish the caller on every native bridge failure.
    suspend fun execute(operation: Fido2Operation, pin: String?): ByteArray {
        if (!isSupported) throw Fido2Exception(Fido2Failure.UNSUPPORTED)
        var produced: ByteArray? = null
        return try {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { continuation ->
                    val handle = NativeFido2.create()
                    if (handle == 0L) {
                        continuation.resumeWithException(Fido2Exception(Fido2Failure.BUSY))
                        return@suspendCancellableCoroutine
                    }
                    continuation.invokeOnCancellation { NativeFido2.cancel(handle) }
                    try {
                        val result = executeAndDecode(handle, operation, pin)
                        produced = result
                        continuation.resume(result) { _, value, _ -> value.fill(0) }
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    } finally {
                        NativeFido2.close(handle)
                    }
                }
            }
        } catch (error: Exception) {
            // withContext can discard a completed result while dispatching back to the caller.
            produced?.fill(0)
            throw error
        }
    }
}

private fun executeAndDecode(handle: Long, operation: Fido2Operation, pin: String?): ByteArray {
    val request = encodeFido2Request(operation, pin)
    val response =
        try {
            NativeFido2.execute(handle, request)
        } finally {
            request.fill(0)
        }
    try {
        return decodeFido2Response(operation, response)
    } finally {
        response.fill(0)
    }
}

internal expect object NativeFido2 {
    val isSupported: Boolean

    fun create(): Long

    fun execute(handle: Long, request: ByteArray): ByteArray

    fun cancel(handle: Long)

    fun close(handle: Long)
}
