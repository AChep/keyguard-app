package com.artemchep.keyguard.util.zxcvbn.bridge

import com.artemchep.keyguard.util.ffi.JniLibrary
import com.artemchep.keyguard.util.zxcvbn.NativeZxcvbnJni
import com.artemchep.keyguard.util.zxcvbn.ZxcvbnException

internal actual object NativeZxcvbn {
    private val library = JniLibrary(
        name = "keyguard_zxcvbn_jni",
        pathProperty = "keyguard.nativeZxcvbn.libraryPath",
        unavailable = ::nativeZxcvbnUnavailable,
        verify = {
            val actual = NativeZxcvbnJni.abiVersion()
            if (actual != NATIVE_ZXCVBN_ABI_VERSION) {
                throw ZxcvbnException(
                    "Unsupported native zxcvbn ABI $actual; expected $NATIVE_ZXCVBN_ABI_VERSION",
                )
            }
        },
    )

    actual fun estimate(
        password: String,
        userInputs: List<String>,
        out: LongArray,
    ): Long = withLibrary {
        NativeZxcvbnJni.estimate(
            password,
            // The hot path passes no user inputs; a null array spares the JNI
            // side an empty-array walk and matches the ABI's "null == empty".
            userInputs.takeIf(List<String>::isNotEmpty)?.toTypedArray(),
            out,
        )
    }

    private inline fun <T> withLibrary(block: () -> T): T {
        return try {
            library.ensureLoaded()
            block()
        } catch (error: UnsatisfiedLinkError) {
            throw nativeZxcvbnUnavailable(error)
        } catch (error: SecurityException) {
            throw nativeZxcvbnUnavailable(error)
        }
    }

    private fun nativeZxcvbnUnavailable(cause: Throwable?): ZxcvbnException =
        ZxcvbnException(
            message = "Native zxcvbn library is unavailable",
            cause = cause,
        )
}
