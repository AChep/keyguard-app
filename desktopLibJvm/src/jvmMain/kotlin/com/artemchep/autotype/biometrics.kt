package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import com.artemchep.jna.withDesktopLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public enum class BiometricsStatus(internal val code: Int) {
    SUCCESS(0),
    USER_CANCELED(1),
    CREDENTIAL_NOT_FOUND(2),
    SECURITY_DEVICE_LOCKED(3),
    UNAVAILABLE(4),
    USER_PREFERS_PASSWORD(5),
    UNKNOWN(6),
    ;

    internal companion object {
        fun fromCode(code: Int): BiometricsStatus =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

public class BiometricsException(
    public val status: BiometricsStatus,
    message: String,
) : RuntimeException(message)

public suspend fun biometricsIsSupported(): Boolean = withContext(Dispatchers.IO) {
    withDesktopLib { lib ->
        lib.biometricsIsSupported()
    }
}

public suspend fun biometricsDeleteCredential() {
    withContext(Dispatchers.IO) {
        withDesktopLib { lib ->
            check(lib.biometricsDeleteCredential() != 0) {
                "Failed to delete the biometric credential."
            }
        }
    }
}

/**
 * Wraps [secret] with a Windows Hello protected key, then immediately asks
 * Windows Hello to unwrap it. A key created by this operation is removed if
 * verification fails, while a pre-existing key is preserved.
 *
 * @param windowHandle native handle of the window that owns the prompt,
 * `0` if unknown.
 */
public suspend fun biometricsWrapSecret(
    windowHandle: Long,
    title: String,
    secret: ByteArray,
): ByteArray = withContext(Dispatchers.IO) {
    withDesktopLib { lib ->
        biometricsTransformSecretOrThrow(
            lib = lib,
            windowHandle = windowHandle,
            title = title,
            input = secret,
            decrypt = false,
        )
    }
}

/**
 * Unwraps a secret previously wrapped by [biometricsWrapSecret]. The platform
 * shows its own prompt while it releases the key.
 */
public suspend fun biometricsUnwrapSecret(
    windowHandle: Long,
    title: String,
    wrappedSecret: ByteArray,
): ByteArray = withContext(Dispatchers.IO) {
    withDesktopLib { lib ->
        biometricsTransformSecretOrThrow(
            lib = lib,
            windowHandle = windowHandle,
            title = title,
            input = wrappedSecret,
            decrypt = true,
        )
    }
}

/**
 * Asks the platform to confirm the user's presence, e.g. Touch ID on macOS or
 * Windows Hello on Windows. Throws a [BiometricsException] when the user does
 * not pass the check.
 *
 * @param windowHandle native handle of the window that owns the prompt,
 * `0` if unknown. Only Windows uses it.
 */
public suspend fun biometricsVerify(
    windowHandle: Long,
    title: String,
) {
    withContext(Dispatchers.IO) {
        biometricsVerifyOrThrow(
            lib = DesktopLibJna.get(),
            windowHandle = windowHandle,
            title = title,
        )
    }
}
