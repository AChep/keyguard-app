package com.artemchep.keyguard.util.fido2

/** iOS apps cannot reach security keys over HID, so no native library is linked. */
internal actual object NativeFido2 {
    actual val isSupported: Boolean = false

    actual fun create(): Long = unsupported()

    actual fun execute(handle: Long, request: ByteArray): ByteArray = unsupported()

    actual fun cancel(handle: Long) = Unit

    actual fun close(handle: Long) = Unit
}

private fun unsupported(): Nothing = throw Fido2Exception(Fido2Failure.UNSUPPORTED)
