package com.artemchep.keyguard.util.io.bridge

import kotlinx.io.IOException
import java.io.File

internal actual object NativeIoLibraryLoader {
    @Volatile
    private var loaded = false

    actual fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val configuredLibrary = System.getProperty(NATIVE_IO_LIBRARY_PATH_PROPERTY)
                    ?.takeIf(String::isNotBlank)
                    ?.let(::File)
                if (configuredLibrary != null) {
                    System.load(configuredLibrary.canonicalPath)
                } else {
                    System.loadLibrary(NATIVE_IO_LIBRARY_NAME)
                }
            } catch (error: UnsatisfiedLinkError) {
                throw IOException("Native IO library is unavailable", error)
            } catch (error: SecurityException) {
                throw IOException("Native IO library is unavailable", error)
            }
            loaded = true
        }
    }
}

private const val NATIVE_IO_LIBRARY_PATH_PROPERTY = "keyguard.nativeIo.libraryPath"
private const val NATIVE_IO_LIBRARY_NAME = "keyguard_io_jni"
