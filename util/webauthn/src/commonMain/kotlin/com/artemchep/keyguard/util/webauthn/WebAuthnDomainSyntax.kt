package com.artemchep.keyguard.util.webauthn

// RFC 1035 caps DNS names at 255 octets on the wire. For a text form without a
// trailing root dot, the wire length is text length + one root label + one
// length octet per label, which is equivalent to text length + 2 <= 255.
// See https://www.rfc-editor.org/rfc/rfc1035#section-2.3.4
internal const val DNS_DOMAIN_NAME_MAX_TEXT_LENGTH_WITHOUT_ROOT_DOT = 253

private const val DNS_LABEL_MAX_LENGTH = 63
private const val IPV4_OCTET_COUNT = 4
private const val IPV4_OCTET_MAX_DIGITS = 3
private const val IPV4_OCTET_MAX_VALUE = 255

internal fun isValidWebAuthnDomainRpId(
    rpId: String,
): Boolean {
    // URL valid domain strings may include a trailing root dot. Remove it only
    // for syntax checks; WebAuthn/HTML matching and `rpIdHash` retain the dot.
    // See https://url.spec.whatwg.org/#valid-domain-string
    val domainName = rpId.removeSuffix(".")
    if (domainName.length !in 1..DNS_DOMAIN_NAME_MAX_TEXT_LENGTH_WITHOUT_ROOT_DOT || isIpv4Address(domainName)) {
        return false
    }
    val labels = domainName.split('.')
    return labels.size >= 2 && labels.all(::isValidWebAuthnDomainLabel)
}

internal fun isValidWebAuthnDomainLabel(
    label: String,
): Boolean {
    val validLength = label.length in 1..DNS_LABEL_MAX_LENGTH
    // RFC 1035 LDH labels contain letters, digits, and hyphens, with a letter
    // or digit at both ends. RFC 1123 permits the leading digit. Domains have
    // already been canonicalized to lowercase before this syntax check.
    // See https://www.rfc-editor.org/rfc/rfc1035#section-2.3.1
    // See https://www.rfc-editor.org/rfc/rfc1123#section-2.1
    val validBoundaries = label.firstOrNull() != '-' && label.lastOrNull() != '-'
    val validCharacters = label.all { char ->
        char in 'a'..'z' || char in '0'..'9' || char == '-'
    }
    return validLength && validBoundaries && validCharacters
}

internal fun Char.isWebAuthnAscii(): Boolean = this <= '\u007f'

private fun isIpv4Address(
    value: String,
): Boolean {
    val parts = value.split('.')
    return parts.size == IPV4_OCTET_COUNT && parts.all { part ->
        val validLength = part.length in 1..IPV4_OCTET_MAX_DIGITS
        val validCharacters = part.all(Char::isDigit)
        val validValue = part.toIntOrNull() in 0..IPV4_OCTET_MAX_VALUE
        validLength && validCharacters && validValue
    }
}
