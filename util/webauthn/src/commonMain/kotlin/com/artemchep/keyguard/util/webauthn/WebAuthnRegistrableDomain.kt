package com.artemchep.keyguard.util.webauthn

// Not a WebAuthn-defined value. This arbitrary DNS-safe label is only used to
// probe whether a candidate domain can be the registrable domain of a child host.
private const val WEBAUTHN_REGISTRABLE_DOMAIN_TEST_LABEL = "keyguard-registrable-domain-test"

internal suspend fun getWebAuthnRegistrableDomain(
    domainLookup: WebAuthnDomainLookup,
    host: String,
): String? {
    val normalizedHost = canonicalizeWebAuthnRpIdOrNull(host)
        ?: return null
    val trailingRootDot = normalizedHost
        .takeLast(1)
        .takeIf { it == "." }
        .orEmpty()
    val lookupHost = normalizedHost.removeSuffix(".")
    val domainName = domainLookup
        .getDomainName(lookupHost)
        .let(::canonicalizeWebAuthnRpIdOrNull)
        ?.let { it + trailingRootDot }

    return domainName
        ?.takeIf {
            it != normalizedHost || isWebAuthnRegistrableDomain(
                domainLookup = domainLookup,
                domainName = it,
            )
        }
}

internal suspend fun isWebAuthnRegistrableDomain(
    domainLookup: WebAuthnDomainLookup,
    domainName: String,
): Boolean {
    val normalizedDomainName = canonicalizeWebAuthnRpIdOrNull(domainName)
        ?: return false
    val trailingRootDot = normalizedDomainName
        .takeLast(1)
        .takeIf { it == "." }
        .orEmpty()
    val lookupDomainName = normalizedDomainName.removeSuffix(".")
    // URL's host public-suffix and registrable-domain algorithms preserve a
    // trailing root dot in their result. WebAuthnDomainLookup does not model that root
    // label, so strip it only for the PSL lookup and reattach it immediately.
    // Do not use this as RP ID normalization; HTML matching keeps the dot
    // significant.
    // See https://url.spec.whatwg.org/#host-public-suffix
    // See https://url.spec.whatwg.org/#host-registrable-domain
    // `WebAuthnDomainLookup.getDomainName(value) == value` is true for both a
    // registrable domain like `example.com` and a bare public suffix like
    // `com` or `co.uk`. Probe a synthetic child host so the public suffix list
    // can tell the two cases apart: `keyguard-...-test.example.com` resolves
    // back to `example.com`, while `keyguard-...-test.com` does not resolve
    // back to `com`.
    return if (lookupDomainName.isBlank()) {
        false
    } else {
        val testHost = "$WEBAUTHN_REGISTRABLE_DOMAIN_TEST_LABEL.$lookupDomainName"
        val testDomainName = domainLookup
            .getDomainName(testHost)
            .let(::canonicalizeWebAuthnRpIdOrNull)
            ?.let { it + trailingRootDot }
        testDomainName == normalizedDomainName
    }
}
