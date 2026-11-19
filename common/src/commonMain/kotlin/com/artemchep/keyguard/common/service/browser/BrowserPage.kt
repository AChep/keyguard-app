package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.feature.auth.common.util.REGEX_IPV4
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath

/**
 * A web page that an extension names in a request: an absolute `http` or
 * `https` URL with a host.
 */
internal class BrowserPage private constructor(
    val url: Url,
) {
    /** `http` or `https`. */
    val scheme: String = url.protocol.name

    /** The host, in lowercase, without a trailing dot. */
    val host: String = url.host.lowercase().trimEnd('.')

    /** The origin of the page, such as `https://login.example.com`, without the default port. */
    val origin: String = originOf(url)

    /**
     * The page, as the matching of the vault and the blocked URIs see it: with
     * the host as [host] spells it, so `https://bank.com./` is `bank.com`.
     */
    val link: LinkInfoPlatform.Web
        get() {
            val normalized = URLBuilder(url).apply { host = this@BrowserPage.host }.build()
            return LinkInfoPlatform.Web(
                url = normalized,
                frontPageUrl = Url(originOf(normalized)),
            )
        }

    /**
     * The site of the page: its scheme and registrable domain, such as
     * `https://example.com`. An IP address or a host without a registrable
     * domain is a site of its own.
     */
    suspend fun site(tldService: TldService): String {
        val domain = if (isIpAddress(host)) {
            host
        } else {
            tldService.getDomainName(host)
                .attempt()
                .bind()
                .getOrNull()
                ?.lowercase()
                ?.trimEnd('.')
                ?.takeIf { it.isNotEmpty() && host.endsWith(it) }
                ?: host
        }
        return "$scheme://$domain"
    }

    companion object {
        private val SCHEMES = setOf("http", "https")

        /** The page of [url], or `null` if it is not an absolute `http` or `https` URL with a host. */
        fun parse(url: String): BrowserPage? = runCatching { Url(url) }
            .getOrNull()
            ?.takeIf { parsed ->
                parsed.protocol.name in SCHEMES &&
                    parsed.host.isNotBlank() &&
                    hasAuthority(url, parsed.protocol.name)
            }
            ?.let(::BrowserPage)
            // A host of dots only, such as `https://./`, has no name left.
            ?.takeIf { it.host.isNotEmpty() }

        /** The scheme, the host and the port of [url], without the default port. */
        private fun originOf(url: Url): String = URLBuilder(url).apply {
            parameters.clear()
            // The `?` of an empty query, such as `https://example.com/sign-in?`.
            trailingQuery = false
            fragment = ""
            encodedPath = ""
            user = null
            password = null
        }.buildString().removeSuffix("/")

        /**
         * Whether [url] starts with `scheme://` and a host. The parser fills in
         * a default host, so `https:/sign-in` or a relative URL would become
         * a page of `localhost`.
         */
        private fun hasAuthority(url: String, scheme: String): Boolean {
            val prefix = "$scheme://"
            val raw = url.trimStart()
            if (!raw.startsWith(prefix, ignoreCase = true)) return false
            val authority = raw.substring(prefix.length).takeWhile { it !in "/?#\\" }
            val hostAndPort = authority.substringAfterLast('@')
            val host = if (hostAndPort.startsWith('[')) hostAndPort else hostAndPort.substringBefore(':')
            return host.isNotEmpty()
        }

        /**
         * The page of the origin [origin], such as `https://example.com:8443`,
         * or `null` if it is not an `http` or `https` origin: a scheme, a host
         * and an optional port, nothing else.
         */
        fun parseOrigin(origin: String): BrowserPage? = parse(origin)
            ?.takeIf { it.origin.equals(origin, ignoreCase = true) }

        private fun isIpAddress(host: String): Boolean = ':' in host || REGEX_IPV4.matches(host)
    }
}
