package com.artemchep.keyguard.nativecrypto

public enum class NativeCryptoErrorCode(
    internal val wireValue: Int?,
) {
    LIBRARY_UNAVAILABLE(null),
    ABI_MISMATCH(null),
    MISSING_CAPABILITY(null),
    MALFORMED_RESPONSE(null),
    INVALID_REQUEST(1),
    UNSUPPORTED_PROTOCOL(2),
    INVALID_ARGUMENT(3),
    RESOURCE_LIMIT(4),
    CRYPTO_FAILURE(5),
    AUTHENTICATION_FAILED(6),
    INVALID_SESSION(7),
    PANIC(8),
    INTERNAL(9),
    UNSUPPORTED_KEY_VERSION(10),
    NO_USABLE_KEY(11),
    ;

    internal companion object {
        fun fromWireValue(value: Int): NativeCryptoErrorCode? =
            entries.firstOrNull { code -> code.wireValue == value }
    }
}

/**
 * A stable, secret-free failure from the native crypto boundary.
 *
 * This extends [IllegalArgumentException] to preserve callers that already treat invalid
 * cryptographic parameters as argument failures. Inspect [code] to distinguish failures.
 */
public class NativeCryptoException(
    public val operation: String,
    public val code: NativeCryptoErrorCode,
) : IllegalArgumentException("Native crypto failed: operation=$operation, code=$code")

internal class NativeCryptoPlatformException(
    val code: NativeCryptoErrorCode,
    cause: Throwable? = null,
) : Exception(cause)
