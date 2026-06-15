package com.artemchep.keyguard.common.service.webauthn

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.tld.TldService

// Not a WebAuthn-defined value. This arbitrary DNS-safe label is only used to
// probe whether a candidate domain can be the registrable domain of a child host.
private const val WEBAUTHN_REGISTRABLE_DOMAIN_TEST_LABEL = "keyguard-registrable-domain-test"

internal suspend fun getWebAuthnRegistrableDomain(
    tldService: TldService,
    host: String,
): String? {
    val normalizedHost = canonicalizeWebAuthnRpIdOrNull(host)
        ?: return null
    val trailingRootDot = normalizedHost
        .takeLast(1)
        .takeIf { it == "." }
        .orEmpty()
    val lookupHost = normalizedHost.removeSuffix(".")
    val domainName = tldService
        .getDomainName(lookupHost)
        .bind()
        .let { canonicalizeWebAuthnRpIdOrNull(it) ?: return null }
        .let { it + trailingRootDot }
    if (domainName != normalizedHost) {
        return domainName
    }

    return domainName
        .takeIf {
            isWebAuthnRegistrableDomain(
                tldService = tldService,
                domainName = it,
            )
        }
}

internal suspend fun isWebAuthnRegistrableDomain(
    tldService: TldService,
    domainName: String,
): Boolean {
    val normalizedDomainName = canonicalizeWebAuthnRpIdOrNull(domainName)
        ?: return false
    val trailingRootDot = normalizedDomainName
        .takeLast(1)
        .takeIf { it == "." }
        .orEmpty()
    val lookupDomainName = normalizedDomainName.removeSuffix(".")
    if (lookupDomainName.isBlank()) {
        return false
    }

    // URL's host public-suffix and registrable-domain algorithms preserve a
    // trailing root dot in their result. TldService does not model that root
    // label, so strip it only for the PSL lookup and reattach it immediately.
    // Do not use this as RP ID normalization; HTML matching keeps the dot
    // significant.
    // See https://url.spec.whatwg.org/#host-public-suffix
    // See https://url.spec.whatwg.org/#host-registrable-domain
    // `TldService.getDomainName(value) == value` is true for both a
    // registrable domain like `example.com` and a bare public suffix like
    // `com` or `co.uk`. Probe a synthetic child host so the public suffix list
    // can tell the two cases apart: `keyguard-...-test.example.com` resolves
    // back to `example.com`, while `keyguard-...-test.com` does not resolve
    // back to `com`.
    val testHost = "$WEBAUTHN_REGISTRABLE_DOMAIN_TEST_LABEL.$lookupDomainName"
    val testDomainName = tldService
        .getDomainName(testHost)
        .bind()
        .let { canonicalizeWebAuthnRpIdOrNull(it) ?: return false }
        .let { it + trailingRootDot }
    return testDomainName == normalizedDomainName
}
