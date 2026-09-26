package com.artemchep.keyguard.util.webauthn

import io.ktor.http.Url
import io.ktor.http.encodedPath

private const val WEBAUTHN_LOCALHOST = "localhost"

internal data class WebAuthnOrigin(
    val serialized: String,
    val host: String,
    val url: Url,
)

fun canonicalizeWebAuthnRpId(
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

fun isValidCanonicalWebAuthnRpId(
    rpId: String,
): Boolean = isWebAuthnLocalhost(rpId) || isValidWebAuthnDomainRpId(rpId)

internal fun isWebAuthnLocalhost(
    host: String,
): Boolean = host == WEBAUTHN_LOCALHOST

internal suspend fun isWebAuthnDomainSuffix(
    domainLookup: WebAuthnDomainLookup,
    domain: String,
    request: String,
): Boolean {
    // WebAuthn delegates this relation to HTML's "is a registrable domain
    // suffix of or is equal to" algorithm, whose examples explicitly keep a
    // trailing root dot significant: `example.com` != `example.com.`.
    // See https://html.spec.whatwg.org/multipage/browsers.html#is-a-registrable-domain-suffix-of-or-is-equal-to
    return when {
        request == domain -> true
        !request.endsWith(suffix = ".$domain", ignoreCase = true) -> false
        else -> {
            // HTML rejects suffix matches that cross the original host's public suffix.
            // With valid domain inputs and a confirmed suffix relation, matching
            // registrable domains is the local equivalent of that public-suffix guard.
            // See https://html.spec.whatwg.org/multipage/browsers.html#is-a-registrable-domain-suffix-of-or-is-equal-to
            val requestRegistrableDomain = getWebAuthnRegistrableDomain(
                domainLookup = domainLookup,
                host = request,
            )
            requestRegistrableDomain != null && requestRegistrableDomain == getWebAuthnRegistrableDomain(
                domainLookup = domainLookup,
                host = domain,
            )
        }
    }
}

internal fun requireWebAuthnOrigin(
    url: Url,
): WebAuthnOrigin = requireNotNull(parseWebAuthnOrigin(url)) {
    "Request origin is not a valid WebAuthn web origin."
}

internal fun parseWebAuthnOrigin(
    url: Url,
): WebAuthnOrigin? {
    if (!hasWebAuthnOriginComponents(url)) {
        return null
    }

    return canonicalizeWebAuthnRpIdOrNull(url.host)
        ?.takeIf { host ->
            // WebAuthn L3 RP ID scoping allows HTTPS web origins, plus the
            // local-development exception `http://localhost:<port>`.
            // See https://www.w3.org/TR/webauthn-3/#rp-id
            val validScheme = url.protocol.name == "https" ||
                url.protocol.name == "http" && isWebAuthnLocalhost(host)
            validScheme && isValidCanonicalWebAuthnRpId(host)
        }
        ?.let { host ->
            val port = url.port
                .takeIf { it != url.protocol.defaultPort }
                ?.let { ":$it" }
                .orEmpty()
            WebAuthnOrigin(
                serialized = "${url.protocol.name}://$host$port",
                host = host,
                url = url,
            )
        }
}

private fun hasWebAuthnOriginComponents(
    url: Url,
): Boolean {
    val isRootPath = url.encodedPath.isEmpty() || url.encodedPath == "/"
    val hasResource = !isRootPath || !url.parameters.isEmpty() ||
        url.trailingQuery || url.encodedFragment.isNotEmpty()
    val hasAuthority = url.host.isNotBlank() && url.user == null && url.password == null
    return hasAuthority && !hasResource
}
