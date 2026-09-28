package com.artemchep.keyguard.util.yubikey

import java.io.File

internal actual object NativeYubiKey {
    // The shipped Flatpak sandbox does not expose /dev/hidraw.
    // Mirrors the Flatpak detection in common's LePlatform.kt; keep both in sync.
    actual val isSupported: Boolean =
        System.getenv("container") != "flatpak" && !File("/.flatpak-info").exists()
    actual fun create(): Long {
        NativeYubiKeyLibraryLoader.ensureLoaded()
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
