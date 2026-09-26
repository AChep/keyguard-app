package com.artemchep.keyguard.util.webauthn

import android.icu.text.IDNA

private const val IDNA_RESERVED_HYPHENS_INDEX = 2

// Android exposes the platform ICU UTS46 implementation from API 24. Keep its
// options aligned with Desktop so WebAuthn RP IDs have the same validation and
// non-transitional domain-to-ASCII behavior on both platforms.
private val WEB_AUTHN_IDNA by lazy {
    IDNA.getUTS46Instance(
        IDNA.USE_STD3_RULES or IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ or IDNA.NONTRANSITIONAL_TO_ASCII,
    )
}

internal actual fun webAuthnDomainToAscii(
    value: String,
): String? {
    // UTS46 maps ordinary ASCII LDH labels only by lowercasing them. Handle
    // that deterministic path locally so JVM host tests do not need Android
    // framework implementations. Unicode and ACE labels still go through
    // Android ICU for mapping, Punycode, Bidi, and ContextJ validation.
    if (value.all(Char::isWebAuthnAscii)) {
        val ascii = value.lowercase()
        return if (ascii.split('.').any { it.startsWith("xn--") }) {
            platformDomainToAscii(value)
        } else {
            ascii.takeIf(::isValidPlainAsciiDomain)
        }
    }

    return platformDomainToAscii(value)
}

private fun platformDomainToAscii(
    value: String,
): String? {
    val info = IDNA.Info()
    val ascii = WEB_AUTHN_IDNA
        .nameToASCII(value, StringBuilder(), info)
        .toString()
    return ascii.takeIf {
        !info.hasErrors() &&
            it.isNotEmpty() &&
            it.all(Char::isWebAuthnAscii)
    }
}

private fun isValidPlainAsciiDomain(value: String): Boolean =
    value.isNotEmpty() &&
        value.length <= DNS_DOMAIN_NAME_MAX_TEXT_LENGTH_WITHOUT_ROOT_DOT &&
        value.split('.').all { label ->
            // Non-ACE labels cannot use the reserved third/fourth-position hyphens.
            isValidWebAuthnDomainLabel(label) &&
                !label.startsWith("--", startIndex = IDNA_RESERVED_HYPHENS_INDEX)
        }
