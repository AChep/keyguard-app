package com.artemchep.keyguard.util.fido2

import java.io.File

internal actual object NativeFido2 {
    // The shipped Flatpak sandbox does not expose /dev/hidraw.
    // Mirrors the Flatpak detection in common's LePlatform.kt; keep all copies listed there in sync.
    actual val isSupported: Boolean =
        System.getenv("container") != "flatpak" && !File("/.flatpak-info").exists()

    actual fun create(): Long {
        NativeFido2LibraryLoader.ensureLoaded()
        return NativeFido2Jni.create()
    }

    actual fun execute(handle: Long, request: ByteArray): ByteArray =
        NativeFido2Jni.execute(handle, request)

    actual fun cancel(handle: Long) = NativeFido2Jni.cancel(handle)

    actual fun close(handle: Long) = NativeFido2Jni.close(handle)
}

internal object NativeFido2Jni {
    external fun abiVersion(): Int

    external fun create(): Long

    external fun execute(handle: Long, request: ByteArray): ByteArray

    external fun cancel(handle: Long)

    external fun close(handle: Long)
}
