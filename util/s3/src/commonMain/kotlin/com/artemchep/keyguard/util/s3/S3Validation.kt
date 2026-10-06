package com.artemchep.keyguard.util.s3

import com.artemchep.keyguard.util.s3.internal.s3CanonicalHost
import io.ktor.http.Url
import kotlin.text.CharacterCodingException

/**
 * Checks a bucket name. Virtual-hosted addressing puts the bucket in a host
 * name, so it needs the strict DNS-compatible rules; path-style addressing
 * also accepts legacy names with uppercase letters and underscores.
 */
fun isValidS3BucketName(
    name: String,
    pathStyle: Boolean = false,
): Boolean = if (pathStyle) {
    name.length in MIN_BUCKET_LENGTH..MAX_LEGACY_BUCKET_LENGTH &&
        name.all { it.isAsciiLetterOrDigit() || it == '.' || it == '-' || it == '_' }
} else {
    isDnsCompatibleBucketName(name)
}

private fun isDnsCompatibleBucketName(
    name: String,
): Boolean = name.length in MIN_BUCKET_LENGTH..MAX_BUCKET_LENGTH &&
    name.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' } &&
    name.first().isAsciiLetterOrDigit() &&
    name.last().isAsciiLetterOrDigit() &&
    INVALID_BUCKET_SEQUENCES.none { it in name } &&
    !IPV4_REGEX.matches(name)

/**
 * Checks an endpoint URL: an `http` or `https` URL with a host and without
 * credentials, a query or a fragment. It may contain a base path, but not
 * `.` or `..` segments that HTTP engines could normalize after signing.
 */
fun isValidS3EndpointUrl(
    value: String,
): Boolean {
    val trimmed = value.trim()
    val hasHttpScheme = trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.startsWith("http://", ignoreCase = true)
    val url = trimmed
        .takeIf { hasHttpScheme && '?' !in it && '#' !in it && s3Utf8SizeOrNull(it) != null }
        ?.let { runCatching { Url(it) }.getOrNull() }
        ?: return false
    return url.user == null &&
        url.password == null &&
        url.segments.none { it == "." || it == ".." } &&
        s3CanonicalHost(url.host) != null
}

/**
 * Checks that a key can be sent unchanged by the supported HTTP engines.
 * Leading, trailing and repeated slashes are preserved, though servers that
 * store objects as files, such as SeaweedFS, may collapse them. Although S3 accepts
 * `.` and some `..` segments, engines such as OkHttp normalize them, even when
 * percent-encoded. Those keys are rejected before I/O to avoid another object.
 */
fun isValidS3ObjectKey(
    key: String,
): Boolean {
    val size = s3Utf8SizeOrNull(key)
    if (size == null || size !in 1..MAX_KEY_BYTES) {
        return false
    }
    return key
        .split('/')
        .none { segment -> segment == "." || segment == ".." }
}

/**
 * Checks an access key ID. It is sent in the `Authorization` header, so it
 * must consist of visible ASCII characters.
 */
fun isValidS3AccessKeyId(
    value: String,
): Boolean = value.isNotEmpty() && value.all { it in '!'..'~' }

/** Invalid UTF-16 must never be replaced with another object's UTF-8 bytes. */
internal fun s3Utf8SizeOrNull(
    value: String,
): Int? = try {
    value.encodeToByteArray(throwOnInvalidSequence = true).size
} catch (_: CharacterCodingException) {
    null
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private val IPV4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

private val INVALID_BUCKET_SEQUENCES = listOf("..", ".-", "-.")

private const val MIN_BUCKET_LENGTH = 3
private const val MAX_BUCKET_LENGTH = 63
private const val MAX_LEGACY_BUCKET_LENGTH = 255
private const val MAX_KEY_BYTES = 1024
