package com.artemchep.keyguard.common.service.s3

import com.artemchep.keyguard.util.s3.isValidS3ObjectKey

/**
 * Normalizes a user-entered prefix: trims it, drops leading and repeated
 * slashes, and ends a non-empty prefix with `/`.
 */
fun normalizeS3Prefix(
    raw: String?,
): String {
    val segments = raw
        ?.trim()
        ?.split('/')
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    return if (segments.isEmpty()) "" else segments.joinToString(separator = "/", postfix = "/")
}

/** Normalizes a user-entered endpoint: trims it and drops trailing slashes. Null means Amazon S3. */
fun normalizeS3Endpoint(
    raw: String?,
): String? = raw
    ?.trim()
    ?.trimEnd('/')
    ?.takeIf { it.isNotEmpty() }

/** Whether [prefix] is the bucket root or a `/`-terminated path of valid segments. */
fun isValidS3Prefix(
    prefix: String,
): Boolean = prefix.isEmpty() ||
    prefix.endsWith('/') && isValidS3ObjectKey(prefix.dropLast(1))

internal fun joinS3Key(
    prefix: String,
    relative: String,
): String = prefix + relative

/** Returns [key] relative to [prefix], or null if the key is outside of it. */
internal fun stripS3Prefix(
    prefix: String,
    key: String,
): String? = if (key.startsWith(prefix)) key.substring(prefix.length) else null
