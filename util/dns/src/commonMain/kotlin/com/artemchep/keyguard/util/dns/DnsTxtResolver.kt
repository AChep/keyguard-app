package com.artemchep.keyguard.util.dns

/**
 * Looks up DNS TXT records through the platform resolver, so a query honours
 * the system's VPN, Private DNS and split-DNS configuration. The desktop
 * resolver is an exception, see `JndiDnsTxtResolver`.
 */
interface DnsTxtResolver {
    /**
     * Returns one entry per TXT record of [name], with the character-strings of
     * each record concatenated (RFC 7208 §3.3). Returns an empty list when the
     * name does not exist or has no TXT records.
     *
     * @throws DnsLookupException when the lookup fails or the response is malformed.
     * @throws IllegalArgumentException when [name] is not a valid DNS name.
     */
    suspend fun lookupTxt(name: String): List<String>
}

/** Creates the resolver backed by the platform DNS API. */
expect fun createDnsTxtResolver(): DnsTxtResolver
