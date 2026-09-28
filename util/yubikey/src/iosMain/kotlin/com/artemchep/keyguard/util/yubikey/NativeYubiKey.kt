package com.artemchep.keyguard.util.yubikey

/** iOS apps cannot reach the OTP HID interface, so no native library is linked. */
internal actual object NativeYubiKey {
    actual val isSupported: Boolean = false
    actual fun create(): Long = unsupported()
    actual fun execute(handle: Long, request: ByteArray): ByteArray = unsupported()
    actual fun cancel(handle: Long) = Unit
    actual fun close(handle: Long) = Unit
}

private fun unsupported(): Nothing = throw YubiKeyException(YubiKeyFailure.UNSUPPORTED)
