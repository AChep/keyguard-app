package com.artemchep.keyguard.util.instance

import com.artemchep.keyguard.util.ffi.JniLibrary

internal actual object NativeInstance {
    private val library = JniLibrary(
        name = "keyguard_instance_jni",
        pathProperty = "keyguard.nativeInstance.libraryPath",
        unavailable = { cause -> InstanceException(InstanceFailureKind.UNAVAILABLE, cause) },
        verify = {
            if (NativeInstanceJni.abiVersion() != INSTANCE_ABI_VERSION) {
                throw InstanceException(InstanceFailureKind.PROTOCOL)
            }
        },
    )

    actual fun lastError(): String? = NativeInstanceJni.lastError()

    actual fun acquireOrActivate(
        coordinationDirectory: String,
        runtimeDirectory: String,
        identity: String,
        timeoutMillis: Long,
    ): Long {
        library.ensureLoaded()
        return NativeInstanceJni.acquireOrActivate(
            coordinationDirectory,
            runtimeDirectory,
            identity,
            timeoutMillis,
        )
    }

    actual fun acquire(
        coordinationDirectory: String,
        runtimeDirectory: String,
        identity: String,
        timeoutMillis: Long,
    ): Long {
        library.ensureLoaded()
        return NativeInstanceJni.acquire(
            coordinationDirectory,
            runtimeDirectory,
            identity,
            timeoutMillis,
        )
    }

    actual fun waitEvent(handle: Long): Long {
        library.ensureLoaded()
        return NativeInstanceJni.waitEvent(handle)
    }

    actual fun stop(handle: Long): Long {
        library.ensureLoaded()
        return NativeInstanceJni.stop(handle)
    }

    actual fun close(handle: Long): Long {
        library.ensureLoaded()
        return NativeInstanceJni.close(handle)
    }
}
