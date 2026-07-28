package com.artemchep.keyguard.nativecrypto

import com.sun.jna.Platform
import java.io.File

internal actual object NativeCryptoLibraryLoader {
    @Volatile
    private var loaded = false

    actual fun ensureLoaded() {
        val library = configuredLibraryOrNull() ?: bundledLibraryOrNull()
            ?: throw NativeCryptoPlatformException(NativeCryptoErrorCode.LIBRARY_UNAVAILABLE)
        loadOnce(library)
    }

    internal fun ensureBundledLibraryLoaded() {
        val bundledLibrary = bundledLibraryOrNull()
            ?: throw NativeCryptoPlatformException(NativeCryptoErrorCode.LIBRARY_UNAVAILABLE)
        loadOnce(bundledLibrary)
    }

    private fun loadOnce(absoluteLibrary: File) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                System.load(absoluteLibrary.canonicalPath)
            } catch (e: UnsatisfiedLinkError) {
                throw NativeCryptoPlatformException(NativeCryptoErrorCode.LIBRARY_UNAVAILABLE, e)
            } catch (e: SecurityException) {
                throw NativeCryptoPlatformException(NativeCryptoErrorCode.LIBRARY_UNAVAILABLE, e)
            }
            loaded = true
        }
    }

    private fun configuredLibraryOrNull(): File? =
        System.getProperty("keyguard.nativeCrypto.libraryPath")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)

    private fun bundledLibraryOrNull(): File? {
        val resourcesDirectory = System.getProperty("compose.application.resources.dir")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: return null
        val candidate = File(resourcesDirectory, platformLibraryFileName())
        return candidate.takeIf(File::isFile)
    }

    private fun platformLibraryFileName(): String = when {
        Platform.isWindows() || Platform.isWindowsCE() -> "keyguard_crypto_jni.dll"
        Platform.isMac() -> "libkeyguard_crypto_jni.dylib"
        Platform.isLinux() -> "libkeyguard_crypto_jni.so"
        else -> throw NativeCryptoPlatformException(NativeCryptoErrorCode.LIBRARY_UNAVAILABLE)
    }
}
