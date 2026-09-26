package com.artemchep.keyguard.util.webauthn

import platform.Foundation.NSURLComponents

internal actual fun webAuthnDomainToAscii(
    value: String,
): String? {
    // Foundation URL parsing is the platform URL implementation available here;
    // use its host canonicalization as the URL domain-to-ASCII equivalent and
    // reject values that do not produce an ASCII host.
    // See https://url.spec.whatwg.org/#host-parsing
    // See https://url.spec.whatwg.org/#concept-domain-to-ascii
    val components = NSURLComponents(string = "https://$value/")
    val host = components.percentEncodedHost
        ?: components.host
    return host
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() && it.all(Char::isWebAuthnAscii) }
}
