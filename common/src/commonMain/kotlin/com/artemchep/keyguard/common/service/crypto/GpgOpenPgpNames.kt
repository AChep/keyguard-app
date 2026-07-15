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
    val start = userId.indexOf('<')
    val end = userId.indexOf('>', startIndex = start + 1)
    if (start in 0..<end) {
        return userId.substring(start + 1, end)
            .trim()
            .takeIf { it.isNotEmpty() }
    }
    // Some user-ids are a bare e-mail without angle brackets.
    return userId.trim().takeIf { it.contains('@') && !it.contains(' ') }
}
