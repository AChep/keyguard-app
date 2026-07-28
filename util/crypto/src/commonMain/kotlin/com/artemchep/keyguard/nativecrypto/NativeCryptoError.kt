package com.artemchep.keyguard.nativecrypto

public enum class NativeCryptoErrorCode(
    internal val wireValue: Int?,
) {
    LIBRARY_UNAVAILABLE(wireValue = null),
    ABI_MISMATCH(wireValue = null),
    MISSING_CAPABILITY(wireValue = null),
    MALFORMED_RESPONSE(wireValue = null),
    INVALID_REQUEST(wireValue = 1),
    UNSUPPORTED_PROTOCOL(wireValue = 2),
    INVALID_ARGUMENT(wireValue = 3),
    RESOURCE_LIMIT(wireValue = 4),
    CRYPTO_FAILURE(wireValue = 5),
    AUTHENTICATION_FAILED(wireValue = 6),
    INVALID_SESSION(wireValue = 7),
    PANIC(wireValue = 8),
    INTERNAL(wireValue = 9),
    UNSUPPORTED_KEY_VERSION(wireValue = 10),
    NO_USABLE_KEY(wireValue = 11),
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
