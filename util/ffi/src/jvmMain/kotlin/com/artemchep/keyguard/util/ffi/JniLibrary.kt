package com.artemchep.keyguard.util.ffi

import java.io.File
import java.io.IOException

/**
 * Loads a JNI library once per process.
 *
 * A non-blank [pathProperty] system property wins; Gradle sets it for host tests.
 * Otherwise desktop loads `System.mapLibraryName(name)` from the Compose resources
 * directory and Android calls `System.loadLibrary(name)`.
 *
 * [unavailable] gets `null` when there is no library, otherwise the
 * [UnsatisfiedLinkError], [SecurityException] or [IOException] from loading.
 * [verify] runs once, inside the lock, before the library counts as loaded. What it
 * throws passes through, except a missing JNI symbol, which maps to [unavailable].
 * A failed attempt is retried by the next call.
 *
 * The library binds to this class's class loader, so the JNI classes must share it.
 */
class JniLibrary internal constructor(
    private val load: () -> Boolean,
    private val unavailable: (cause: Throwable?) -> Throwable,
    private val verify: () -> Unit,
) {
    constructor(
        name: String,
        pathProperty: String,
        unavailable: (cause: Throwable?) -> Throwable,
        verify: () -> Unit = {},
    ) : this(
        load = { loadJniLibrary(name, pathProperty) },
        unavailable = unavailable,
        verify = verify,
    )

    @Volatile
    private var loaded = false

    // Each catch throws the module's own failure; [unavailable] for a missing library
    // is thrown outside the try because it may itself be an IOException.
    @Suppress("ThrowsCount")
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val found = try {
                load()
            } catch (error: UnsatisfiedLinkError) {
                throw unavailable(error)
            } catch (error: SecurityException) {
                throw unavailable(error)
            } catch (error: IOException) {
                throw unavailable(error)
            }
            if (!found) throw unavailable(null)
            try {
                verify()
            } catch (error: UnsatisfiedLinkError) {
                throw unavailable(error)
            } catch (error: SecurityException) {
                throw unavailable(error)
            }
            loaded = true
        }
    }
}

/** Returns `false` when there is no library to load. */
internal fun loadJniLibrary(name: String, pathProperty: String): Boolean {
    val configured = System.getProperty(pathProperty)?.takeIf(String::isNotBlank)
    if (configured != null) {
        System.load(File(configured).canonicalPath)
        return true
    }
    return loadBundledJniLibrary(name)
}

internal expect fun loadBundledJniLibrary(name: String): Boolean
