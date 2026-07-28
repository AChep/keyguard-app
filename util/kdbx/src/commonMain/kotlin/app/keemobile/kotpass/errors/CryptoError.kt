package app.keemobile.kotpass.errors

sealed class CryptoError(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class InvalidDataLength(override val message: String) : CryptoError()

    class MaxBytesExceeded(override val message: String) : CryptoError()

    class AlgorithmUnavailable(
        override val message: String,
        cause: Throwable? = null,
    ) : CryptoError(message, cause)

    class InvalidKey(override val message: String) : CryptoError()

    class InvalidCipherText(override val message: String) : CryptoError()
}
