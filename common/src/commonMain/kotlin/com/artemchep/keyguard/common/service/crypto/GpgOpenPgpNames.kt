package com.artemchep.keyguard.common.service.crypto

// Numeric OpenPGP public-key algorithm ids (RFC 4880 + later additions).
// Hard-coded so the domain mapping is independent of parser implementation details.
internal fun gpgAlgorithmName(
    algorithm: Int,
): String = when (algorithm) {
    1, 2, 3 -> "RSA"
    16, 20 -> "ELGAMAL"
    17 -> "DSA"
    18 -> "ECDH"
    19 -> "ECDSA"
    22 -> "EDDSA"
    25 -> "X25519"
    26 -> "X448"
    27 -> "ED25519"
    28 -> "ED448"
    else -> "ALGO_$algorithm"
}

internal fun extractGpgUserIdEmail(
    userId: String,
): String? {
    // Checked once up front; the substrings below split at BMP delimiters, so
    // they cannot introduce a broken surrogate pair.
    if (!userId.hasValidUnicodeScalars()) {
        return null
    }
    if (userId.isValidGpgMailboxStructure()) {
        return userId
    }
    // OpenPGP User IDs have no required internal structure. For deriving an
    // address, follow Sequoia's anchored "Conventional User ID" shape instead
    // of recovering an address from malformed or ambiguous text.
    val addressStart = userId.lastIndexOf('<')
    return userId.takeIf { it.endsWith('>') && addressStart >= 0 }
        ?.substring(addressStart + 1, userId.lastIndex)
        ?.takeIf(String::isValidGpgMailboxStructure)
        ?.takeIf {
            userId.substring(0, addressStart).isValidGpgUserIdAddressPrefix()
        }
}

/** Normalizes an input that is already specified to be a bare mailbox. */
internal fun normalizeGpgMailboxAddress(
    address: String,
): String? = address
    .trim()
    .takeIf(String::isValidGpgMailboxAddress)
    ?.lowercase()

internal fun normalizeGpgUserIdEmail(
    userId: String,
): String? = extractGpgUserIdEmail(userId)
    ?.lowercase()

private fun String.isValidGpgMailboxAddress(): Boolean =
    hasValidUnicodeScalars() && isValidGpgMailboxStructure()

/** Requires the receiver to already be a valid Unicode scalar sequence. */
private fun String.isValidGpgMailboxStructure(): Boolean {
    val separator = indexOf('@')
    return separator > 0 &&
            separator == lastIndexOf('@') &&
            substring(0, separator).isValidGpgDotAtom() &&
            substring(separator + 1).isValidGpgDotAtom()
}

private fun String.isValidGpgDotAtom(): Boolean =
    split('.').all { atom ->
        atom.isNotEmpty() && atom.all(Char::isGpgAtext)
    }

private fun Char.isGpgAtext(): Boolean =
    this in 'A'..'Z' ||
            this in 'a'..'z' ||
            this in '0'..'9' ||
            this in GPG_ATEXT_PUNCTUATION ||
            code >= GPG_NON_ASCII_CODE_POINT_START && !isISOControl()

// The only caller has already validated the whole User ID, and the prefix is
// split at a BMP delimiter, so no surrogate pair can be broken here.
private fun String.isValidGpgUserIdAddressPrefix(): Boolean {
    val value = trim { it == ' ' }
    if (value.all(Char::isGpgNameChar)) {
        return true
    }
    // A name may end with exactly one parenthesized comment.
    if (!value.endsWith(')')) {
        return false
    }
    val commentStart = value.lastIndexOf('(')
    if (commentStart < 0) {
        return false
    }
    val comment = value.substring(commentStart + 1, value.lastIndex)
    if (!comment.all(Char::isGpgCommentChar)) {
        return false
    }
    return value.substring(0, commentStart)
        .trimEnd { it == ' ' }
        .all(Char::isGpgNameChar)
}

private fun Char.isGpgNameChar(): Boolean =
    !isISOControl() && this != '<' && this != '>'

private fun Char.isGpgCommentChar(): Boolean =
    !isISOControl() && this != '(' && this != ')'

private fun String.hasValidUnicodeScalars(): Boolean =
    runCatching { encodeToByteArray(throwOnInvalidSequence = true) }.isSuccess

private const val GPG_ATEXT_PUNCTUATION = "!#$%&'*+-/=?^_`{|}~"
private const val GPG_NON_ASCII_CODE_POINT_START = 0x80
