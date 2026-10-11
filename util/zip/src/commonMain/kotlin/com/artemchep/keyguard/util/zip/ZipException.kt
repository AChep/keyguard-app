package com.artemchep.keyguard.util.zip

/** Thrown when an archive cannot be written or read. */
open class ZipException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** A password-protected read encountered an entry without authenticated AES encryption. */
class ZipEncryptionRequiredException : ZipException(
    "Password-protected archives must use AES encryption for every entry",
)
