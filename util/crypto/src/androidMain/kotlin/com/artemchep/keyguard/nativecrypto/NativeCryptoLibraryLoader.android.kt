package com.artemchep.keyguard.nativecrypto

import java.io.File

internal actual object NativeCryptoLibraryLoader {
    @Volatile
    private var loaded = false

    actual fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val configuredLibrary = System.getProperty("keyguard.nativeCrypto.libraryPath")
                    ?.takeIf(String::isNotBlank)
                    ?.let(::File)
                if (configuredLibrary != null) {
                    System.load(configuredLibrary.canonicalPath)
                } else {
                    System.loadLibrary("keyguard_crypto_jni")
                }
            } catch (e: UnsatisfiedLinkError) {
                throw NativeCryptoPlatformException(NativeCryptoErrorCode.LIBRARY_UNAVAILABLE, e)
            } catch (e: SecurityException) {
                throw NativeCryptoPlatformException(NativeCryptoErrorCode.LIBRARY_UNAVAILABLE, e)
            }
            loaded = true
        }
    }
}
