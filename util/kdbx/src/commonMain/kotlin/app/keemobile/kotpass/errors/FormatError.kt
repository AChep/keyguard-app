package app.keemobile.kotpass.errors

sealed class FormatError : Exception() {
    class UnknownFormat(override val message: String) : FormatError()

    class UnsupportedVersion(override val message: String) : FormatError()

    class InvalidHeader(override val message: String) : FormatError()

    class InvalidContent(override val message: String) : FormatError()

    class InvalidXml(override val message: String) : FormatError()

    class FailedCompression(override val message: String) : FormatError()
}
