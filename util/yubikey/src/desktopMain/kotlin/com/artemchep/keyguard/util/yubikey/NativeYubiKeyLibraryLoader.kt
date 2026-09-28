package com.artemchep.keyguard.util.yubikey

import java.io.File

internal object NativeYubiKeyLibraryLoader {
    @Volatile
    private var loaded = false

    // Preserve ABI failures while mapping each JVM library-loading failure to UNSUPPORTED.
    @Suppress("ThrowsCount")
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val library = configuredLibraryOrNull() ?: bundledLibraryOrNull()
                    ?: throw YubiKeyException(YubiKeyFailure.UNSUPPORTED)
                System.load(library.canonicalPath)
                if (NativeYubiKeyJni.abiVersion() != YUBIKEY_ABI_VERSION) {
                    throw YubiKeyException(YubiKeyFailure.PROTOCOL)
                }
            } catch (error: UnsatisfiedLinkError) {
                throw YubiKeyException(YubiKeyFailure.UNSUPPORTED, error)
            } catch (error: SecurityException) {
                throw YubiKeyException(YubiKeyFailure.UNSUPPORTED, error)
            } catch (error: java.io.IOException) {
                throw YubiKeyException(YubiKeyFailure.UNSUPPORTED, error)
            }
            loaded = true
        }
    }

    private fun configuredLibraryOrNull(): File? =
        System.getProperty("keyguard.nativeYubikey.libraryPath")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)

    private fun bundledLibraryOrNull(): File? {
        val directory = System.getProperty("compose.application.resources.dir")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: return null
        return File(directory, platformLibraryFileName()).takeIf(File::isFile)
    }

    private fun platformLibraryFileName(): String {
        val operatingSystem = System.getProperty("os.name").orEmpty()
        return when {
            operatingSystem.startsWith("Windows", ignoreCase = true) -> "keyguard_yubikey_jni.dll"
            operatingSystem.startsWith("Mac", ignoreCase = true) -> "libkeyguard_yubikey_jni.dylib"
            operatingSystem.startsWith("Linux", ignoreCase = true) -> "libkeyguard_yubikey_jni.so"
            else -> throw YubiKeyException(YubiKeyFailure.UNSUPPORTED)
        }
    }
}
