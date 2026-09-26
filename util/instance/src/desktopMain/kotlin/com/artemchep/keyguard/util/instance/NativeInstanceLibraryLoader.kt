package com.artemchep.keyguard.util.instance

import java.io.File

internal object NativeInstanceLibraryLoader {
    @Volatile
    private var loaded = false

    // Preserve ABI failures while mapping each JVM library-loading failure to UNAVAILABLE.
    @Suppress("ThrowsCount")
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val library = configuredLibraryOrNull() ?: bundledLibraryOrNull()
                    ?: throw InstanceException(InstanceFailureKind.UNAVAILABLE)
                System.load(library.canonicalPath)
                if (NativeInstanceJni.abiVersion() != INSTANCE_ABI_VERSION) {
                    throw InstanceException(InstanceFailureKind.PROTOCOL)
                }
            } catch (error: UnsatisfiedLinkError) {
                throw InstanceException(InstanceFailureKind.UNAVAILABLE, error)
            } catch (error: SecurityException) {
                throw InstanceException(InstanceFailureKind.UNAVAILABLE, error)
            } catch (error: java.io.IOException) {
                throw InstanceException(InstanceFailureKind.UNAVAILABLE, error)
            }
            loaded = true
        }
    }

    private fun configuredLibraryOrNull(): File? =
        System.getProperty("keyguard.nativeInstance.libraryPath")
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
            operatingSystem.startsWith("Windows", ignoreCase = true) -> "keyguard_instance_jni.dll"
            operatingSystem.startsWith("Mac", ignoreCase = true) -> "libkeyguard_instance_jni.dylib"
            operatingSystem.startsWith("Linux", ignoreCase = true) -> "libkeyguard_instance_jni.so"
            else -> throw InstanceException(InstanceFailureKind.UNAVAILABLE)
        }
    }
}
