package com.artemchep.keyguard.util.fido2

import java.io.File

internal object NativeFido2LibraryLoader {
    @Volatile private var loaded = false

    // Preserve ABI failures while mapping each JVM library-loading failure to UNSUPPORTED.
    @Suppress("ThrowsCount")
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val library =
                    configuredLibraryOrNull()
                        ?: bundledLibraryOrNull()
                        ?: throw Fido2Exception(Fido2Failure.UNSUPPORTED)
                System.load(library.canonicalPath)
                if (NativeFido2Jni.abiVersion() != FIDO2_ABI_VERSION) {
                    throw Fido2Exception(Fido2Failure.PROTOCOL)
                }
            } catch (error: UnsatisfiedLinkError) {
                throw Fido2Exception(Fido2Failure.UNSUPPORTED, error)
            } catch (error: SecurityException) {
                throw Fido2Exception(Fido2Failure.UNSUPPORTED, error)
            } catch (error: java.io.IOException) {
                throw Fido2Exception(Fido2Failure.UNSUPPORTED, error)
            }
            loaded = true
        }
    }

    private fun configuredLibraryOrNull(): File? =
        System.getProperty("keyguard.nativeFido2.libraryPath")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)

    private fun bundledLibraryOrNull(): File? {
        val directory =
            System.getProperty("compose.application.resources.dir")
                ?.takeIf(String::isNotBlank)
                ?.let(::File) ?: return null
        return File(directory, System.mapLibraryName("keyguard_fido2_jni")).takeIf(File::isFile)
    }
}
