package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import com.artemchep.keyguard.common.util.PROTOCOL_ANDROID_APP
import com.artemchep.keyguard.common.util.PROTOCOL_IOS_APP
import com.artemchep.keyguard.common.util.ensureUrlScheme
import io.ktor.http.DEFAULT_PORT
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
// TODO: Add special treatments for androidapp:// etc.
class CipherUrlCheckImpl(
    private val tldService: TldService,
) : CipherUrlCheck {
    constructor(directDI: DirectDI) : this(
        tldService = directDI.instance(),
    )

    override fun invoke(
        uri: DSecret.Uri,
        url: String,
        defaultMatchDetection: DSecret.Uri.MatchType,
        equivalentDomains: EquivalentDomains,
    ): IO<Boolean> {
        return when (uri.match ?: defaultMatchDetection) {
            DSecret.Uri.MatchType.Domain -> {
                val shouldUseHostMatchInstead = uri.uri.startsWith(PROTOCOL_ANDROID_APP) ||
                        url.startsWith(PROTOCOL_ANDROID_APP) ||
                        uri.uri.startsWith(PROTOCOL_IOS_APP) ||
                        url.startsWith(PROTOCOL_IOS_APP)
                if (shouldUseHostMatchInstead) {
                    ::checkUrlMatchByHost
                } else {
                    ::checkUrlMatchByDomain
                }
            }

            DSecret.Uri.MatchType.Host -> ::checkUrlMatchByHost
            DSecret.Uri.MatchType.StartsWith -> ::checkUrlMatchByStartsWith
            DSecret.Uri.MatchType.Exact -> ::checkUrlMatchByExact
            DSecret.Uri.MatchType.RegularExpression -> ::checkUrlMatchByRegularExpression
            DSecret.Uri.MatchType.Never -> ::checkUrlMatchByNever
        }.invoke(uri.uri, url, equivalentDomains)
    }

    private fun checkUrlMatchByDomain(
        a: String,
        b: String,
        equivalentDomains: EquivalentDomains,
    ): IO<Boolean> = ioEffect {
        val aHost = urlOf(a).host
        val bHost = urlOf(b).host
        // Find the actual domain name from the host name. This
        // is quite tricky as there are quite a lot of very different
        // company owned names.
        val bDomain = tldService
            .getDomainName(bHost)
            .bind()
        val bDomainEq = equivalentDomains.findEqDomains(bDomain)
        val normalizedAHost = aHost.normalizeDomainSuffixPart()
        bDomainEq.any { normalizedAHost.hasDomainSuffix(it.normalizeDomainSuffixPart()) }
    }

    private fun checkUrlMatchByHost(
        a: String,
        b: String,
        equivalentDomains: EquivalentDomains,
    ): IO<Boolean> = ioEffect {
        val aUrl = urlOf(a)
        val bUrl = urlOf(b)

        val bDomain = tldService
            .getDomainName(bUrl.host)
            .bind()
        val bDomainEq = equivalentDomains.findEqDomains(bDomain)

        bDomainEq.any {
            val bHost = bUrl.host.replaceDomainSuffix(
                domain = bDomain,
                replacement = it,
            )

            // If the CIPHER url doesn't have a port specified, then
            // match it with any port.
            if (aUrl.specifiedPort == DEFAULT_PORT) {
                return@any compareIgnoreCase(aUrl.host, bHost)
            }
            // If the TEST url doesn't have a port specified, then
            // match it with any port. This is specifically needed
            // because on Android there's no way to pull a port number
            // from a browser.
            if (bUrl.specifiedPort == DEFAULT_PORT) {
                return@any compareIgnoreCase(aUrl.host, bHost)
            }
            compareIgnoreCase(aUrl.host, bHost) && aUrl.port == bUrl.port
        }
    }

    private fun checkUrlMatchByStartsWith(
        a: String,
        b: String,
        equivalentDomains: EquivalentDomains,
    ): IO<Boolean> = ioEffect {
        val aFiltered = a.trim().removeSuffix("/")
        val bFiltered = b.trim().removeSuffix("/")
        // Slow proper path:
        runCatching {
            val bUrl = URLBuilder(b)
            val bDomain = tldService
                .getDomainName(bUrl.host)
                .bind()
            val bDomainEq = equivalentDomains.findEqDomains(bDomain)
            bDomainEq.any { domain ->
                val url = URLBuilder(bUrl).apply {
                    host = host.replaceDomainSuffix(
                        domain = bDomain,
                        replacement = domain,
                    )
                }.buildString()
                url.startsWith(aFiltered)
            }
        }.getOrElse {
            // Fast path:
            bFiltered.startsWith(aFiltered)
        }
    }

    private fun checkUrlMatchByExact(
        a: String,
        b: String,
        // An equivalent domain will be negated for an item that uses exact match detection.
        // For example, an item with the saved URI apple.com set to Exact will not offer autofill
        // for icloud.com despite that being a default equivalent.
        // https://bitwarden.com/help/uri-match-detection/#equivalent-domains
        equivalentDomains: EquivalentDomains,
    ): IO<Boolean> = ioEffect {
        val aFiltered = a.trim().removeSuffix("/")
        val bFiltered = b.trim().removeSuffix("/")
        aFiltered == bFiltered
    }

    private fun checkUrlMatchByRegularExpression(
        a: String,
        b: String,
        equivalentDomains: EquivalentDomains,
    ): IO<Boolean> = ioEffect {
        // URIs are mostly case-insensitive, so it makes sense
        // that regular expressions should also be case-insensitive.
        val aRegex = a.toRegex(RegexOption.IGNORE_CASE)
        // Slow proper path:
        runCatching {
            val bUrl = URLBuilder(b)
            val bDomain = tldService
                .getDomainName(bUrl.host)
                .bind()
            val bDomainEq = equivalentDomains.findEqDomains(bDomain)
            bDomainEq.any { domain ->
                val url = URLBuilder(bUrl).apply {
                    host = host.replaceDomainSuffix(
                        domain = bDomain,
                        replacement = domain,
                    )
                }.buildString()
                url.matches(aRegex)
            }
        }.getOrElse {
            // Fast path:
            b.matches(aRegex)
        }
    }

    private fun checkUrlMatchByNever(
        a: String,
        b: String,
        equivalentDomains: EquivalentDomains,
    ): IO<Boolean> = io(false)

    private fun urlOf(url: String): Url {
        val newUrl = ensureUrlScheme(url)
        return Url(newUrl)
    }

    private fun compareIgnoreCase(a: String, b: String) = a.contentEquals(b, ignoreCase = true)

    private fun String.replaceDomainSuffix(
        domain: String,
        replacement: String,
    ): String {
        val host = normalizeDomainSuffixPart()
        val normalizedDomain = domain.normalizeDomainSuffixPart()
        if (!host.hasDomainSuffix(normalizedDomain)) {
            return this
        }
        val prefix = host.dropLast(normalizedDomain.length)
        return prefix + replacement
    }

    private fun String.hasDomainSuffix(
        domain: String,
    ): Boolean {
        if (domain.isEmpty()) {
            return false
        }
        val prefixLength = length - domain.length
        if (prefixLength < 0) {
            return false
        }
        if (!endsWith(domain, ignoreCase = true)) {
            return false
        }
        return prefixLength == 0 || this[prefixLength - 1] == '.'
    }

    private fun String.normalizeDomainSuffixPart(): String = trim().removeSuffix(".")
}
