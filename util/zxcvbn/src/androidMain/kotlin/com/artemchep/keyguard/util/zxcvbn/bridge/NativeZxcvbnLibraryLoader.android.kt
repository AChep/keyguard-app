package com.artemchep.keyguard.util.zxcvbn.bridge

import com.artemchep.keyguard.util.zxcvbn.ZxcvbnException
import java.io.File

internal actual object NativeZxcvbnLibraryLoader {
    @Volatile
    private var loaded = false

    actual fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val configuredLibrary = System.getProperty(NATIVE_ZXCVBN_LIBRARY_PATH_PROPERTY)
                    ?.takeIf(String::isNotBlank)
                    ?.let(::File)
                if (configuredLibrary != null) {
                    System.load(configuredLibrary.canonicalPath)
                } else {
                    System.loadLibrary(NATIVE_ZXCVBN_LIBRARY_NAME)
                }
            } catch (error: UnsatisfiedLinkError) {
                throw ZxcvbnException("Native zxcvbn library is unavailable", error)
            } catch (error: SecurityException) {
                throw ZxcvbnException("Native zxcvbn library is unavailable", error)
            }
            loaded = true
        }
    }
}

private const val NATIVE_ZXCVBN_LIBRARY_PATH_PROPERTY = "keyguard.nativeZxcvbn.libraryPath"
private const val NATIVE_ZXCVBN_LIBRARY_NAME = "keyguard_zxcvbn_jni"
