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
            val library = configuredLibraryOrNull() ?: bundledLibraryOrNull()
                ?: nativeIoUnavailable()
            try {
                System.load(library.canonicalPath)
            } catch (error: UnsatisfiedLinkError) {
                nativeIoUnavailable(error)
            } catch (error: SecurityException) {
                nativeIoUnavailable(error)
            }
            loaded = true
        }
    }

    private fun nativeIoUnavailable(cause: Throwable? = null): Nothing =
        throw IOException("Native IO library is unavailable", cause)

    private fun configuredLibraryOrNull(): File? =
        System.getProperty(NATIVE_IO_LIBRARY_PATH_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(::File)

    private fun bundledLibraryOrNull(): File? {
        val resourcesDirectory = System.getProperty(COMPOSE_RESOURCES_DIRECTORY_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: return null
        val candidate = File(resourcesDirectory, platformLibraryFileName())
        return candidate.takeIf(File::isFile)
    }

    private fun platformLibraryFileName(): String {
        val operatingSystem = System.getProperty("os.name").orEmpty()
        return when {
            operatingSystem.startsWith("Windows", ignoreCase = true) ->
                "keyguard_io_jni.dll"

            operatingSystem.startsWith("Mac", ignoreCase = true) ->
                "libkeyguard_io_jni.dylib"

            operatingSystem.startsWith("Linux", ignoreCase = true) ->
                "libkeyguard_io_jni.so"

            else -> throw IOException(
                "Native IO is unsupported on '$operatingSystem'",
            )
        }
    }
}

private const val NATIVE_IO_LIBRARY_PATH_PROPERTY = "keyguard.nativeIo.libraryPath"
private const val COMPOSE_RESOURCES_DIRECTORY_PROPERTY = "compose.application.resources.dir"
