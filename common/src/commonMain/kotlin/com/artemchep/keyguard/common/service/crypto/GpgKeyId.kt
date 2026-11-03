package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.util.isHexDigit

private const val GPG_V4_FINGERPRINT_LENGTH = 40
internal const val GPG_V6_FINGERPRINT_LENGTH = 64
internal const val GPG_LONG_KEY_ID_LENGTH = 16
private const val GPG_SHORT_KEY_ID_LENGTH = 8

/**
 * Normalized V4 (SHA-1) or V6 (SHA-256) fingerprint, or `null` if the
 * string is not one. Accepts an optional `0x` prefix and whitespace,
 * colons or dashes between the digits.
 *
 * Use it to check untrusted input, such as typed text, QR codes or keyserver
 * responses. Compare stored fingerprints with [normalizeGpgFingerprint] instead.
 */
fun String.parseGpgFingerprintOrNull(): String? = normalizeGpgKeyHexOrNull()
    ?.takeIf { it.length == GPG_V4_FINGERPRINT_LENGTH || it.length == GPG_V6_FINGERPRINT_LENGTH }

/**
 * Normalized long or short key ID, or `null` if the string is not one.
 * Accepts an optional `0x` prefix and whitespace, colons or dashes
 * between the digits.
 */
fun String.parseGpgKeyIdOrNull(): String? = normalizeGpgKeyHexOrNull()
    ?.takeIf { it.length == GPG_LONG_KEY_ID_LENGTH || it.length == GPG_SHORT_KEY_ID_LENGTH }

/** Long key ID for supported V4 (SHA-1) and V6 (SHA-256) fingerprints. */
fun String.gpgKeyIdFromFingerprintOrNull(): String? {
    val fingerprint = parseGpgFingerprintOrNull() ?: return null
    return if (fingerprint.length == GPG_V4_FINGERPRINT_LENGTH) {
        fingerprint.takeLast(GPG_LONG_KEY_ID_LENGTH)
    } else {
        fingerprint.take(GPG_LONG_KEY_ID_LENGTH)
    }
}

// The `0x` prefix is how GnuPG prints key IDs, see `gpg --keyid-format 0xlong`.
// Whitespace, colons and dashes may separate the digits, as in `gpg --fingerprint`
// or `AB:CD:EF`; any other character makes it free text, such as the e-mail
// address `ed@cafe.de`.
private fun String.normalizeGpgKeyHexOrNull(): String? = trim()
    .let { if (it.startsWith("0x", ignoreCase = true)) it.substring(2) else it }
    .filterNot { it.isWhitespace() || it == ':' || it == '-' }
    .uppercase()
    .takeIf { hex -> hex.isNotEmpty() && hex.all { it.isHexDigit() } }
