package com.artemchep.keyguard.util.io

/**
 * Converts this native filesystem path to a platform-correct `file:` URI.
 */
expect fun LocalPath.toFileUriString(): String

/**
 * Converts a local `file:` URI to its native filesystem path.
 */
expect fun String.toLocalPathFromFileUriOrNull(): LocalPath?
