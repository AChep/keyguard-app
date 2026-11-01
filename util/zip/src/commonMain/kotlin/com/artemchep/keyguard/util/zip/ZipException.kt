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

/**
 * Reading an encrypted entry failed because the password is missing or wrong.
 * The two are not told apart: the format cannot, for a missing password reads
 * like an empty one.
 */
class ZipPasswordException(
    message: String = "The archive password is missing or wrong",
    cause: Throwable? = null,
) : ZipException(message, cause)
