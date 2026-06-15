package com.artemchep.keyguard.common.service.webauthn

// WebAuthn defines an RP ID as a URL "valid domain string", and URL validates
// that through domain-to-ASCII rather than simple lowercase normalization.
// Platform actuals return null for domain-to-ASCII failure, including malformed
// ACE/punycode labels.
// See https://www.w3.org/TR/webauthn-3/#rp-id
// See https://url.spec.whatwg.org/#valid-domain-string
// See https://url.spec.whatwg.org/#concept-domain-to-ascii
internal expect fun webAuthnDomainToAscii(
    value: String,
): String?
