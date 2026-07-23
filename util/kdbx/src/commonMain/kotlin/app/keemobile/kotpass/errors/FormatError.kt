package app.keemobile.kotpass.errors

sealed class FormatError(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class UnknownFormat(override val message: String) : FormatError()

    class UnsupportedVersion(override val message: String) : FormatError()

    class InvalidHeader(override val message: String) : FormatError()

    class InvalidContent(override val message: String) : FormatError()

    class InvalidXml(
        override val message: String,
        cause: Throwable? = null,
    ) : FormatError(message, cause)

    class FailedCompression(override val message: String) : FormatError()
}
