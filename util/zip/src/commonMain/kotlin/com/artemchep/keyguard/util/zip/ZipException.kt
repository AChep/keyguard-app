package com.artemchep.keyguard.util.zip

/** Thrown when an archive cannot be written or read. */
class ZipException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
