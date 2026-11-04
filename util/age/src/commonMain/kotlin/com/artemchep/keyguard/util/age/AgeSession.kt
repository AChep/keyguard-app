package com.artemchep.keyguard.util.age

/** Stable errors without native diagnostics, file names, or passwords. */
enum class AgeFailure { Invalid, Password, Corrupt, Io, Limit, Internal, Unavailable }

class AgeException(val reason: AgeFailure, cause: Throwable? = null) :
    RuntimeException("Age archive operation failed: $reason", cause)

internal class AgeSession : AutoCloseable {
    private val handle = NativeAge.create().also {
        if (it <= 0L) throw AgeException(AgeFailure.Unavailable)
    }
    private var closed = false

    /** Sends the first [size] bytes of [body]. */
    fun call(command: Int, body: ByteArray = byteArrayOf(), size: Int = body.size): ByteArray {
        check(!closed) { "Age session is closed" }
        val request = ByteArray(1 + size)
        request[0] = command.toByte()
        body.copyInto(request, destinationOffset = 1, endIndex = size)
        try {
            val response = NativeAge.execute(handle, request)
            try {
                val status = response.firstOrNull()?.toInt()
                    ?: throw AgeException(AgeFailure.Corrupt)
                if (status != 0) {
                    val reason = AgeFailure.entries.getOrNull(status - 1) ?: AgeFailure.Internal
                    throw AgeException(reason)
                }
                return response.copyOfRange(1, response.size)
            } finally {
                response.fill(0)
            }
        } finally {
            request.fill(0)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        NativeAge.close(handle)
    }
}
