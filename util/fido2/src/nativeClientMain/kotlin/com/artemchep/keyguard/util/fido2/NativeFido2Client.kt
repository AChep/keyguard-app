package com.artemchep.keyguard.util.fido2

import com.artemchep.keyguard.util.ffi.runNativeOperation

/** Native USB client for desktop; device handles exist only during an operation. */
class NativeFido2Client {
    val isSupported: Boolean
        get() = NativeFido2.isSupported

    /**
     * Returns a credential ID for registration, or a 32-byte PRF result for derivation. The caller
     * owns the result and must clear secret bytes after use.
     */
    suspend fun execute(operation: Fido2Operation, pin: String?): ByteArray {
        if (!isSupported) throw Fido2Exception(Fido2Failure.UNSUPPORTED)
        return runNativeOperation(
            create = NativeFido2::create,
            cancel = NativeFido2::cancel,
            close = NativeFido2::close,
            busy = { Fido2Exception(Fido2Failure.BUSY) },
            clear = { result -> result.fill(0) },
        ) { handle -> executeAndDecode(handle, operation, pin) }
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
