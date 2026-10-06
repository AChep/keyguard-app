package com.artemchep.keyguard.util.dns

/** A DNS lookup failed. */
open class DnsLookupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The response does not follow the DNS wire format. */
class DnsFormatException(
    message: String,
) : DnsLookupException(message)

/** The response was truncated and no complete answer is available. */
class DnsTruncatedException : DnsLookupException("DNS response is truncated.")

/** The server answered with an error response code. */
class DnsResponseCodeException(
    val rcode: Int,
) : DnsLookupException("DNS server answered with response code $rcode.")

/** The platform has no DNS API that can query TXT records. */
class UnsupportedDnsLookupException :
    DnsLookupException("DNS TXT lookups are not supported on this platform.")
