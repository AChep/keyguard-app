package com.artemchep.keyguard.common.service.icon

import com.artemchep.keyguard.feature.auth.common.util.verifyIsLocalUrl
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.takeFrom

/**
 * Parses a http(s) URL with a host. Unlike [Url], does not
 * turn a URL without a host into a `localhost` one.
 */
fun parseHttpUrlOrNull(url: String): Url? = runCatching {
    val builder = URLBuilder().takeFrom(url)
    if (builder.host.isBlank()) {
        return@runCatching null
    }
    builder.build()
        .takeIf { it.isHttp() }
}.getOrNull()

/** The website a favicon belongs to, or `null` if the [url] is not a http(s) website. */
fun faviconOrigin(url: String): Url? {
    val parsed = parseHttpUrlOrNull(url)
        ?: return null
    return URLBuilder(
        protocol = parsed.protocol,
        host = parsed.host.lowercase(),
        port = parsed.specifiedPort,
    ).build()
}

/**
 * The request for the website of the [FaviconUrl.url]: the icon does
 * not depend on the rest of the URL, so pages of a website share it.
 */
fun FaviconUrl.toWebsiteRequest(): FaviconUrl = faviconOrigin(url)
    ?.let { origin -> copy(url = origin.toString()) }
    ?: this

/**
 * The host to ask an account's icon server about, or `null` if the
 * [url] must not be sent to it: local addresses and non-default ports.
 */
fun iconServerLookupHost(url: Url): String? {
    // The default ports are the same as
    // if the port was not specified.
    val defaultPort = url.specifiedPort in defaultPorts
    return url.host.takeIf { defaultPort && !verifyIsLocalUrl(url) }
}

/**
 * Whether an icon found on a website may be downloaded. A public
 * website can not point the loader at a local address.
 */
fun isFetchableIconUrl(
    url: Url,
    siteIsLocal: Boolean,
): Boolean = url.isHttp() && url.host.isNotBlank() && (siteIsLocal || !verifyIsLocalUrl(url))

private const val UNSPECIFIED_PORT = 0
private const val HTTP_PORT = 80
private const val HTTPS_PORT = 443

private val defaultPorts = setOf(UNSPECIFIED_PORT, HTTP_PORT, HTTPS_PORT)

internal fun Url.isHttp() = protocol == URLProtocol.HTTP || protocol == URLProtocol.HTTPS
