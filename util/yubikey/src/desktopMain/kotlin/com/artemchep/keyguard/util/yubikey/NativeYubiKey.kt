package com.artemchep.keyguard.util.yubikey

import com.artemchep.keyguard.util.ffi.JniLibrary

internal actual object NativeYubiKey {
    private val library = JniLibrary(
        name = "keyguard_yubikey_jni",
        pathProperty = "keyguard.nativeYubikey.libraryPath",
        unavailable = { cause -> YubiKeyException(YubiKeyFailure.UNSUPPORTED, cause) },
        verify = {
            if (NativeYubiKeyJni.abiVersion() != YUBIKEY_ABI_VERSION) {
                throw YubiKeyException(YubiKeyFailure.PROTOCOL)
            }
        },
    )

    actual val isSupported: Boolean = true
    actual fun create(): Long {
        library.ensureLoaded()
        return NativeYubiKeyJni.create()
    }
    actual fun execute(handle: Long, request: ByteArray): ByteArray = NativeYubiKeyJni.execute(handle, request)
    actual fun cancel(handle: Long) = NativeYubiKeyJni.cancel(handle)
    actual fun close(handle: Long) = NativeYubiKeyJni.close(handle)
}

internal object NativeYubiKeyJni {
    external fun abiVersion(): Int
    external fun create(): Long
    external fun execute(handle: Long, request: ByteArray): ByteArray
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}
