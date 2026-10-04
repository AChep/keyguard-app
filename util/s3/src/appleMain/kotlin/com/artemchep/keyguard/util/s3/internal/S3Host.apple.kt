package com.artemchep.keyguard.util.s3.internal

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.Foundation.NSURLComponents
import platform.darwin.inet_ntop
import platform.darwin.inet_pton
import platform.posix.AF_INET6
import platform.posix.INET6_ADDRSTRLEN
import platform.posix.in6_addr

internal actual fun s3CanonicalHost(
    host: String,
): String? {
    val unbracketed = host.removeSurrounding("[", "]")
    return when {
        ':' in unbracketed -> canonicalIpv6Host(unbracketed)
        '[' in host || ']' in host -> null
        else -> canonicalDnsHost(host)
    }
}

private fun canonicalDnsHost(
    host: String,
): String? {
    val components = NSURLComponents().apply {
        scheme = "https"
        this.host = host
    }
    // NSURL.host returns the IDNA form; NSURLComponents.host is Unicode and
    // percentEncodedHost uses percent escapes rather than DNS-compatible IDNA.
    return components.URL?.host
        ?.lowercase()
        ?.takeIf { value ->
            value.isNotEmpty() && value.all { it in '!'..'~' && it !in "#%/:?@[\\]" }
        }
}

@OptIn(ExperimentalForeignApi::class)
private fun canonicalIpv6Host(
    host: String,
): String? = memScoped {
    val address = alloc<in6_addr>()
    if (inet_pton(AF_INET6, host, address.ptr) != 1) {
        return@memScoped null
    }
    val buffer = allocArray<ByteVar>(INET6_ADDRSTRLEN)
    inet_ntop(AF_INET6, address.ptr, buffer, INET6_ADDRSTRLEN.convert())?.toKString()
}
