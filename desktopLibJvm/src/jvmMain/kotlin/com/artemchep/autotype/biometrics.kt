package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import com.artemchep.jna.withDesktopLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CREDENTIAL_NOT_FOUND_CODE = 2
private const val SECURITY_DEVICE_LOCKED_CODE = 3
private const val UNAVAILABLE_CODE = 4
private const val USER_PREFERS_PASSWORD_CODE = 5
private const val UNKNOWN_CODE = 6
private const val POLICY_NOT_INSTALLED_CODE = 7

public enum class BiometricsStatus(internal val code: Int) {
    SUCCESS(0),
    USER_CANCELED(1),
    CREDENTIAL_NOT_FOUND(CREDENTIAL_NOT_FOUND_CODE),
    SECURITY_DEVICE_LOCKED(SECURITY_DEVICE_LOCKED_CODE),
    UNAVAILABLE(UNAVAILABLE_CODE),
    USER_PREFERS_PASSWORD(USER_PREFERS_PASSWORD_CODE),
    UNKNOWN(UNKNOWN_CODE),

    /**
     * Linux only: the polkit policy that declares the unlock
     * action is missing and could not be installed.
     */
    POLICY_NOT_INSTALLED(POLICY_NOT_INSTALLED_CODE),
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

/**
 * Prepares the platform for enrolling the unlock credential. On Linux this
 * installs the polkit policy that declares the unlock action and may ask for
 * administrator approval; other platforms return at once. Throws a
 * [BiometricsException] when the platform cannot be prepared.
 */
public suspend fun biometricsPrepareEnrollment() {
    withContext(Dispatchers.IO) {
        biometricsPrepareEnrollmentOrThrow(
            lib = DesktopLibJna.get(),
        )
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
 * On Linux, stores [secret] in protected process-local storage and returns an
 * opaque handle. This does not prompt; enrollment must verify the user first.
 *
 * On Windows, wraps [secret] with a Windows Hello protected key, then immediately asks
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
): ByteArray {
    var pending: ByteArray? = null
    try {
        return withContext(Dispatchers.IO) {
            withDesktopLib { lib ->
                biometricsTransformSecretOrThrow(
                    lib = lib,
                    windowHandle = windowHandle,
                    title = title,
                    input = wrappedSecret,
                    decrypt = true,
                ).also { pending = it }
            }
        }.also { pending = null }
    } finally {
        // withContext may discard a result if its caller was cancelled while
        // the native prompt was running. Erase it unless ownership transferred.
        pending?.fill(0)
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
