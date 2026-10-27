package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.crypto.parseGpgFingerprintOrNull

// The fingerprint URI that OpenPGP apps (OpenKeychain, Delta Chat, ...)
// encode in QR codes. Upper case keeps the whole payload in the QR
// alphanumeric mode, which yields a smaller code.
private const val GPG_FINGERPRINT_URI_PREFIX = "OPENPGP4FPR:"

fun String.normalizeGpgFingerprint(): String =
    filter(Char::isLetterOrDigit).uppercase()

fun String.chunkedGpgFingerprint(): String =
    chunked(4).joinToString(separator = " ")

fun String.normalizeGpgKeygrip(): String =
    trim().uppercase()

/**
 * Returns the `OPENPGP4FPR:` URI of a fingerprint, or `null` if the
 * string is not a V4 or V6 fingerprint, such as a key ID.
 */
fun String.toGpgFingerprintUriOrNull(): String? = parseGpgFingerprintOrNull()
    ?.let { GPG_FINGERPRINT_URI_PREFIX + it }

/**
 * Returns the normalized fingerprint of an `OPENPGP4FPR:` URI, or `null`
 * if the string is not one. The parameters that Delta Chat appends after
 * a `#` are dropped.
 */
fun String.parseGpgFingerprintUriOrNull(): String? = trim()
    .takeIf { it.startsWith(GPG_FINGERPRINT_URI_PREFIX, ignoreCase = true) }
    ?.substring(GPG_FINGERPRINT_URI_PREFIX.length)
    ?.substringBefore('#')
    ?.parseGpgFingerprintOrNull()
