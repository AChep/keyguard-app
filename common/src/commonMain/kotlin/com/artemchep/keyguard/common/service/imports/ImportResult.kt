package com.artemchep.keyguard.common.service.imports

/**
 * The outcome of parsing a source into an [ImportPlan]: an export file, a
 * snapshot of a Keyguard backup, or a CXF document another app sent.
 */
sealed interface ImportResult {
    data class Success(
        val plan: ImportPlan,
    ) : ImportResult

    data class Failure(
        val error: ImportError,
    ) : ImportResult
}

/**
 * A parse-level failure. Per-credential and per-item problems never surface
 * here — they become counted skips on the [plan][ImportPlan.skips].
 */
sealed interface ImportError : ImportOpenError {
    /**
     * The source is not what it claims to be: it is malformed, misses the
     * members or columns its format requires, or is in a different format
     * entirely.
     */
    data object Parse : ImportError

    /**
     * The source declares a version of its format this implementation does
     * not understand, e.g. a CXF document of a newer major version.
     */
    data object UnsupportedVersion : ImportError

    /**
     * The file exceeds the size an import reads into memory.
     */
    data object TooLarge : ImportError

    /**
     * The file could not be read at all, or the backup is damaged or was
     * written by a newer version of Keyguard.
     */
    data object Unreadable : ImportError

    /**
     * The file or backup is password-protected and no password was given.
     */
    data object PasswordRequired : ImportError

    data object WrongPassword : ImportError

    /**
     * The importer does not support the file's encryption, e.g. account-key
     * encryption or ZIP encryption in a format that only supports plaintext.
     */
    data object UnsupportedEncryption : ImportError
}
