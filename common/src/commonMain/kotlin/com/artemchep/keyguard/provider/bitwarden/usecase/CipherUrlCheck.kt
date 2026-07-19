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
import com.artemchep.keyguard.common.util.parseHttpUrlHostOrNull
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
    private val neverMatchResult = io(false)

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
                    checkUrlMatchByHost(uri.uri, url, equivalentDomains)
                } else {
                    checkUrlMatchByDomain(uri.uri, url, equivalentDomains)
                }
            }

            DSecret.Uri.MatchType.Host -> checkUrlMatchByHost(uri.uri, url, equivalentDomains)
            DSecret.Uri.MatchType.StartsWith -> checkUrlMatchByStartsWith(uri.uri, url, equivalentDomains)
            DSecret.Uri.MatchType.Exact -> checkUrlMatchByExact(uri.uri, url, equivalentDomains)
            DSecret.Uri.MatchType.RegularExpression -> {
                checkUrlMatchByRegularExpression(uri.uri, url, equivalentDomains)
            }

            DSecret.Uri.MatchType.Never -> checkUrlMatchByNever(uri.uri, url, equivalentDomains)
        }
    }

    private fun checkUrlMatchByDomain(
        a: String,
        b: String,
        equivalentDomains: EquivalentDomains,
    ): IO<Boolean> = ioEffect {
        val aHost = hostOf(a)
        val bHost = hostOf(b)
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
        val simpleAHost = simpleHostOf(a)
        val simpleBHost = simpleHostOf(b)
        if (simpleAHost != null && simpleBHost != null) {
            val bDomain = tldService
                .getDomainName(simpleBHost)
                .bind()
            val bDomainEq = equivalentDomains.findEqDomains(bDomain)
            return@ioEffect bDomainEq.any {
                val bHost = simpleBHost.replaceDomainSuffix(
                    domain = bDomain,
                    replacement = it,
                )
                compareIgnoreCase(simpleAHost, bHost)
            }
        }

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
            val originalHost = bUrl.host
            bDomainEq.any { domain ->
                bUrl.host = originalHost.replaceDomainSuffix(
                    domain = bDomain,
                    replacement = domain,
                )
                val url = bUrl.buildString()
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
        a.equalsTrimmedWithoutTrailingSlash(b)
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
            val originalHost = bUrl.host
            bDomainEq.any { domain ->
                bUrl.host = originalHost.replaceDomainSuffix(
                    domain = bDomain,
                    replacement = domain,
                )
                val url = bUrl.buildString()
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
    ): IO<Boolean> = neverMatchResult

    private fun urlOf(url: String): Url {
        val newUrl = ensureUrlScheme(url)
        return Url(newUrl)
    }

    private fun hostOf(url: String): String = simpleHostOf(url) ?: urlOf(url).host

    private fun simpleHostOf(url: String): String? {
        parseHttpUrlHostOrNull(url)?.let { host ->
            return host
        }
        if (url.isEmpty() || url.first() == '.' || url.last() == '.') {
            return null
        }
        var previousWasDot = false
        url.forEach { char ->
            val isHostCharacter = char in 'a'..'z' ||
                    char in '0'..'9' ||
                    char == '.' ||
                    char == '-'
            if (!isHostCharacter || char == '.' && previousWasDot) {
                return null
            }
            previousWasDot = char == '.'
        }
        return url
    }

    private fun compareIgnoreCase(a: String, b: String) = a.contentEquals(b, ignoreCase = true)

    private fun String.equalsTrimmedWithoutTrailingSlash(other: String): Boolean {
        var start = 0
        while (start < length && this[start].isWhitespace()) {
            start += 1
        }
        var end = length
        while (end > start && this[end - 1].isWhitespace()) {
            end -= 1
        }
        if (end > start && this[end - 1] == '/') {
            end -= 1
        }

        var otherStart = 0
        while (otherStart < other.length && other[otherStart].isWhitespace()) {
            otherStart += 1
        }
        var otherEnd = other.length
        while (otherEnd > otherStart && other[otherEnd - 1].isWhitespace()) {
            otherEnd -= 1
        }
        if (otherEnd > otherStart && other[otherEnd - 1] == '/') {
            otherEnd -= 1
        }

        val contentLength = end - start
        if (contentLength != otherEnd - otherStart) {
            return false
        }
        repeat(contentLength) { offset ->
            if (this[start + offset] != other[otherStart + offset]) {
                return false
            }
        }
        return true
    }

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
