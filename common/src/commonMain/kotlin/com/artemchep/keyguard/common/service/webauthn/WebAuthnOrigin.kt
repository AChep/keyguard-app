package com.artemchep.keyguard.common.service.webauthn

import com.artemchep.keyguard.common.service.tld.TldService
import io.ktor.http.Url
import io.ktor.http.encodedPath

private const val WEBAUTHN_LOCALHOST = "localhost"

// RFC 1035 caps DNS names at 255 octets on the wire. For a text form without a
// trailing root dot, the wire length is text length + one root label + one
// length octet per label, which is equivalent to text length + 2 <= 255.
// See https://www.rfc-editor.org/rfc/rfc1035#section-2.3.4
private const val DNS_DOMAIN_NAME_MAX_TEXT_LENGTH_WITHOUT_ROOT_DOT = 253

internal data class WebAuthnOrigin(
    val serialized: String,
    val host: String,
    val url: Url,
)

internal fun canonicalizeWebAuthnRpId(
    value: String,
): String = canonicalizeWebAuthnRpIdOrNull(value) ?: value.lowercase()

internal fun canonicalizeWebAuthnRpIdOrNull(
    value: String,
): String? {
    val trailingRootDot = value
        .takeLast(1)
        .takeIf { it == "." }
        .orEmpty()
    val domainName = value.removeSuffix(".")
    if (domainName.isEmpty()) {
        return null
    }

    // WebAuthn delegates RP ID domain handling to URL's domain-to-ASCII.
    // Preserve an ASCII trailing root dot for HTML matching and rpIdHash input.
    // See https://url.spec.whatwg.org/#concept-domain-to-ascii
    // See https://html.spec.whatwg.org/multipage/browsers.html#is-a-registrable-domain-suffix-of-or-is-equal-to
    return webAuthnDomainToAscii(domainName)
        ?.let { it + trailingRootDot }
}

internal fun isValidCanonicalWebAuthnRpId(
    rpId: String,
): Boolean = isWebAuthnLocalhost(rpId) || isValidDomainRpId(rpId)

internal fun isWebAuthnLocalhost(
    host: String,
): Boolean = host == WEBAUTHN_LOCALHOST

internal suspend fun isWebAuthnDomainSuffix(
    tldService: TldService,
    domain: String,
    request: String,
): Boolean {
    // WebAuthn delegates this relation to HTML's "is a registrable domain
    // suffix of or is equal to" algorithm, whose examples explicitly keep a
    // trailing root dot significant: `example.com` != `example.com.`.
    // See https://html.spec.whatwg.org/multipage/browsers.html#is-a-registrable-domain-suffix-of-or-is-equal-to
    if (request == domain) {
        return true
    }
    if (!request.endsWith(
            suffix = ".$domain",
            ignoreCase = true,
        )
    ) {
        return false
    }

    // HTML rejects suffix matches that cross the original host's public suffix.
    // With valid domain inputs and a confirmed suffix relation, matching
    // registrable domains is the local equivalent of that public-suffix guard.
    // See https://html.spec.whatwg.org/multipage/browsers.html#is-a-registrable-domain-suffix-of-or-is-equal-to
    val requestRegistrableDomain = getWebAuthnRegistrableDomain(
        tldService = tldService,
        host = request,
    ) ?: return false
    val domainRegistrableDomain = getWebAuthnRegistrableDomain(
        tldService = tldService,
        host = domain,
    ) ?: return false
    return requestRegistrableDomain == domainRegistrableDomain
}

internal fun requireWebAuthnOrigin(
    url: Url,
): WebAuthnOrigin = requireNotNull(parseWebAuthnOrigin(url)) {
    "Request origin is not a valid WebAuthn web origin."
}

internal fun parseWebAuthnOrigin(
    url: Url,
): WebAuthnOrigin? {
    if (url.host.isBlank()) {
        return null
    }
    if (url.encodedPath.isNotEmpty() && url.encodedPath != "/") {
        return null
    }
    if (!url.parameters.isEmpty()) {
        return null
    }
    if (url.trailingQuery || url.encodedFragment.isNotEmpty()) {
        return null
    }
    if (url.user != null || url.password != null) {
        return null
    }

    val host = canonicalizeWebAuthnRpIdOrNull(url.host)
        ?: return null

    // WebAuthn L3 RP ID scoping allows HTTPS web origins, plus the
    // local-development exception `http://localhost:<port>`.
    // See https://www.w3.org/TR/webauthn-3/#rp-id
    val validScheme = url.protocol.name == "https" ||
            url.protocol.name == "http" && isWebAuthnLocalhost(host)
    if (!validScheme) {
        return null
    }
    if (!isValidCanonicalWebAuthnRpId(host)) {
        return null
    }

    val port = url.port
        .takeIf { it != url.protocol.defaultPort }
        ?.let { ":$it" }
        .orEmpty()
    return WebAuthnOrigin(
        serialized = "${url.protocol.name}://$host$port",
        host = host,
        url = url,
    )
}

private fun isValidDomainRpId(
    rpId: String,
): Boolean {
    val domainName = rpId.removeSuffix(".")
    if (
        domainName.isEmpty() ||
        domainName.length > DNS_DOMAIN_NAME_MAX_TEXT_LENGTH_WITHOUT_ROOT_DOT ||
        ':' in rpId || // indirectly rejects IPv6 :check:
        '/' in rpId ||
        '@' in rpId ||
        isIpv4Address(domainName)
    ) {
        return false
    }

    val labels = domainName.split('.')
    if (labels.size < 2) {
        return false
    }

    return labels.all { label ->
        // URL valid domain strings may include a trailing root dot. We remove
        // that dot only for syntax checks; the original RP ID keeps it for
        // WebAuthn/HTML matching and `rpIdHash` input.
        // See https://url.spec.whatwg.org/#valid-domain-string
        // See https://www.rfc-editor.org/rfc/rfc1035#section-2.3.4
        val validLength = label.length in 1..63
        // Host labels follow LDH syntax: RFC 1035 requires a letter/digit at
        // the end, and RFC 1123 relaxes the first character to letter/digit.
        // See https://www.rfc-editor.org/rfc/rfc1035#section-2.3.1
        // See https://www.rfc-editor.org/rfc/rfc1123#section-2.1
        val validBoundaries = label.firstOrNull() != '-' &&
                label.lastOrNull() != '-'
        // RFC 1035 LDH syntax allows letters, digits, and hyphen inside host
        // labels; RFC 1123 keeps that character set while allowing leading
        // digits. The RP ID has already been canonicalized to lowercase.
        // See https://www.rfc-editor.org/rfc/rfc1035#section-2.3.1
        // See https://www.rfc-editor.org/rfc/rfc1123#section-2.1
        val validCharacters = label.all { char ->
            char in 'a'..'z' ||
                    char in '0'..'9' ||
                    char == '-'
        }
        validLength && validBoundaries && validCharacters
    }
}

private fun isIpv4Address(
    value: String,
): Boolean {
    val parts = value.split('.')
    if (parts.size != 4) {
        return false
    }
    return parts.all { part ->
        val validLength = part.length in 1..3
        val validCharacters = part.all(Char::isDigit)
        val validValue = part.toIntOrNull() in 0..255
        validLength && validCharacters && validValue
    }
}
