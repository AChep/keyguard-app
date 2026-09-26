package com.artemchep.keyguard.util.instance

internal actual object NativeInstance {
    actual fun lastError(): String? = NativeInstanceJni.lastError()

    actual fun acquireOrActivate(
        coordinationDirectory: String,
        runtimeDirectory: String,
        identity: String,
        timeoutMillis: Long,
    ): Long {
        NativeInstanceLibraryLoader.ensureLoaded()
        return NativeInstanceJni.acquireOrActivate(
            coordinationDirectory,
            runtimeDirectory,
            identity,
            timeoutMillis,
        )
    }

    actual fun waitEvent(handle: Long): Long {
        NativeInstanceLibraryLoader.ensureLoaded()
        return NativeInstanceJni.waitEvent(handle)
    }

    actual fun stop(handle: Long): Long {
        NativeInstanceLibraryLoader.ensureLoaded()
        return NativeInstanceJni.stop(handle)
    }

    actual fun close(handle: Long): Long {
        NativeInstanceLibraryLoader.ensureLoaded()
        return NativeInstanceJni.close(handle)
    }
}
