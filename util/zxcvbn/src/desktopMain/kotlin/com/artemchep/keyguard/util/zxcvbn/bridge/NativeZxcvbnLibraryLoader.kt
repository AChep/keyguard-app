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
            val library = configuredLibraryOrNull() ?: bundledLibraryOrNull()
                ?: nativeZxcvbnUnavailable()
            try {
                System.load(library.canonicalPath)
            } catch (error: UnsatisfiedLinkError) {
                nativeZxcvbnUnavailable(error)
            } catch (error: SecurityException) {
                nativeZxcvbnUnavailable(error)
            }
            loaded = true
        }
    }

    private fun nativeZxcvbnUnavailable(cause: Throwable? = null): Nothing =
        throw ZxcvbnException("Native zxcvbn library is unavailable", cause)

    private fun configuredLibraryOrNull(): File? =
        System.getProperty(NATIVE_ZXCVBN_LIBRARY_PATH_PROPERTY)
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
                "keyguard_zxcvbn_jni.dll"

            operatingSystem.startsWith("Mac", ignoreCase = true) ->
                "libkeyguard_zxcvbn_jni.dylib"

            operatingSystem.startsWith("Linux", ignoreCase = true) ->
                "libkeyguard_zxcvbn_jni.so"

            else -> throw ZxcvbnException(
                "Native zxcvbn is unsupported on '$operatingSystem'",
            )
        }
    }
}

private const val NATIVE_ZXCVBN_LIBRARY_PATH_PROPERTY = "keyguard.nativeZxcvbn.libraryPath"
private const val COMPOSE_RESOURCES_DIRECTORY_PROPERTY = "compose.application.resources.dir"
