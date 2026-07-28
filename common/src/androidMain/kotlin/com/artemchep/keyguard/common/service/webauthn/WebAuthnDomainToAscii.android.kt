package com.artemchep.keyguard.common.service.webauthn

import android.icu.text.IDNA

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
    if (value.all(Char::isAscii)) {
        val ascii = value.lowercase()
        if (ascii.split('.').any { it.startsWith("xn--") }) {
            return platformDomainToAscii(value)
        }
        return ascii.takeIf(::isValidPlainAsciiDomain)
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
                it.all(Char::isAscii)
    }
}

private fun isValidPlainAsciiDomain(value: String): Boolean =
    value.isNotEmpty() &&
            value.length <= 253 &&
            value.split('.').all { label ->
                label.length in 1..63 &&
                        label.first() != '-' &&
                        label.last() != '-' &&
                        label.all { char ->
                            char in 'a'..'z' ||
                                    char in '0'..'9' ||
                                    char == '-'
                        } &&
                        !(label.length >= 4 && label[2] == '-' && label[3] == '-')
            }

private fun Char.isAscii(): Boolean = code in 0x00..0x7f
