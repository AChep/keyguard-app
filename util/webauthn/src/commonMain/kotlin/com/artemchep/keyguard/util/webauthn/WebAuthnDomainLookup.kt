package com.artemchep.keyguard.util.webauthn

/**
 * Public-suffix-list lookup. Returns the registrable domain, or the input host
 * when no registrable domain exists. The validator handles the latter case by
 * probing a child domain, so bare public suffixes are never accepted as RP IDs.
 */
fun interface WebAuthnDomainLookup {
    suspend fun getDomainName(host: String): String
}
