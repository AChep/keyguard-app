package com.artemchep.keyguard.common.service.webauthn

import com.ibm.icu.text.IDNA

// URL domain-to-ASCII uses UTS46 with Bidi and ContextJ checks, STD3 rules in
// strict validation mode, non-transitional processing, and invalid punycode
// treated as failure. ICU exposes those controls through these flags; the shared
// RP ID validator performs the remaining WebAuthn domain syntax checks.
// See https://url.spec.whatwg.org/#concept-domain-to-ascii
// See https://unicode.org/reports/tr46/#ToASCII
private val WEB_AUTHN_IDNA = IDNA.getUTS46Instance(
    IDNA.USE_STD3_RULES or IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ or IDNA.NONTRANSITIONAL_TO_ASCII,
)

internal actual fun webAuthnDomainToAscii(
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

private fun Char.isAscii(): Boolean = code in 0x00..0x7f
