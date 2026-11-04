package com.artemchep.keyguard.util.age

import com.artemchep.keyguard.util.ffi.JniLibrary

internal actual object NativeAge {
    private val library = JniLibrary(
        name = "keyguard_age_jni",
        pathProperty = "keyguard.nativeAge.libraryPath",
        unavailable = { cause -> AgeException(AgeFailure.Unavailable, cause) },
        verify = {
            if (NativeAgeJni.abiVersion() != AGE_ABI_VERSION) {
                throw AgeException(AgeFailure.Corrupt)
            }
        },
    )

    actual fun create(): Long {
        library.ensureLoaded()
        return NativeAgeJni.create()
    }

    actual fun execute(handle: Long, request: ByteArray): ByteArray =
        NativeAgeJni.execute(handle, request)

    actual fun close(handle: Long) = NativeAgeJni.close(handle)
}

internal object NativeAgeJni {
    external fun abiVersion(): Int

    external fun create(): Long

    external fun execute(handle: Long, request: ByteArray): ByteArray

    external fun close(handle: Long)
}
