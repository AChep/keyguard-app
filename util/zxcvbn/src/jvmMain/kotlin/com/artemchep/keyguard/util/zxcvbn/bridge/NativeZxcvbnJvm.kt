package com.artemchep.keyguard.util.zxcvbn.bridge

import com.artemchep.keyguard.util.zxcvbn.NativeZxcvbnJni
import com.artemchep.keyguard.util.zxcvbn.ZxcvbnException

internal expect object NativeZxcvbnLibraryLoader {
    fun ensureLoaded()
}

internal actual object NativeZxcvbn {
    @Volatile
    private var abiVerified = false

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
            NativeZxcvbnLibraryLoader.ensureLoaded()
            ensureCompatibleAbi()
            block()
        } catch (error: ZxcvbnException) {
            throw error
        } catch (error: UnsatisfiedLinkError) {
            nativeZxcvbnUnavailable(error)
        } catch (error: SecurityException) {
            nativeZxcvbnUnavailable(error)
        }
    }

    private fun ensureCompatibleAbi() {
        if (abiVerified) return
        synchronized(this) {
            if (abiVerified) return
            val actual = try {
                NativeZxcvbnJni.abiVersion()
            } catch (error: UnsatisfiedLinkError) {
                nativeZxcvbnUnavailable(error)
            } catch (error: SecurityException) {
                nativeZxcvbnUnavailable(error)
            }
            if (actual != NATIVE_ZXCVBN_ABI_VERSION) {
                throw ZxcvbnException(
                    "Unsupported native zxcvbn ABI $actual; expected $NATIVE_ZXCVBN_ABI_VERSION",
                )
            }
            abiVerified = true
        }
    }

    private fun nativeZxcvbnUnavailable(cause: Throwable): Nothing =
        throw ZxcvbnException(
            message = "Native zxcvbn library is unavailable",
            cause = cause,
        )
}
