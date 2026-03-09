package com.artemchep.keyguard.common.service.extract.impl

import arrow.core.Either
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import com.artemchep.keyguard.common.util.PROTOCOL_ANDROID_APP
import com.artemchep.keyguard.common.util.PROTOCOL_IOS_APP
import com.artemchep.keyguard.feature.auth.common.util.REGEX_IPV4
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath
import kotlin.reflect.KClass

class LinkInfoPlatformExtractor : LinkInfoExtractor<DSecret.Uri, LinkInfoPlatform> {
    companion object {
        private const val HTTP_SCHEME_PREFIX = "http://"
        private const val HTTPS_SCHEME_PREFIX = "https://"
    }

    override val from: KClass<DSecret.Uri> get() = DSecret.Uri::class

    override val to: KClass<LinkInfoPlatform> get() = LinkInfoPlatform::class

    override fun extractInfo(uri: DSecret.Uri): IO<LinkInfoPlatform> = ioEffect {
        val url = uri.uri
        when {
            url.startsWith(PROTOCOL_ANDROID_APP, ignoreCase = true) ->
                createAndroidPlatform(uri)

            url.startsWith(PROTOCOL_IOS_APP, ignoreCase = true) ->
                createIOSPlatform(uri)

            url.startsWith(HTTP_SCHEME_PREFIX, ignoreCase = true) ||
                    url.startsWith(HTTPS_SCHEME_PREFIX, ignoreCase = true) ->
                createWebPlatform(url)

            else -> {
                val result = classifyInput(uri)
                when (result) {
                    ClassifyInputResult.URL -> createWebPlatform(url)
                    ClassifyInputResult.SEARCH -> LinkInfoPlatform.Other
                }
            }
        }
    }

    private fun createAndroidPlatform(uri: DSecret.Uri): LinkInfoPlatform {
        val packageName = uri.uri
            .substring(PROTOCOL_ANDROID_APP.length)
        return LinkInfoPlatform.Android(
            packageName = packageName,
        )
    }

    private fun createIOSPlatform(uri: DSecret.Uri): LinkInfoPlatform {
        val packageName = uri.uri
            .substring(PROTOCOL_IOS_APP.length)
        return LinkInfoPlatform.IOS(
            bundleId = packageName,
        )
    }

    private fun createWebPlatform(rawUrl: String): LinkInfoPlatform {
        val normalizedUrl = normalizeWebUrl(rawUrl)
        val parsedUri = Either
            .catch {
                Url(normalizedUrl)
            }
            .getOrNull()
        // failed to parse the url, probably we can not open it
            ?: return LinkInfoPlatform.Other

        val frontPageUrl = URLBuilder(parsedUri).apply {
            parameters.clear()
            fragment = ""
            encodedPath = ""
        }.build()
        return LinkInfoPlatform.Web(
            url = parsedUri,
            frontPageUrl = frontPageUrl,
        )
    }

    override fun handles(uri: DSecret.Uri): Boolean = true
}

private fun normalizeWebUrl(
    rawUrl: String,
): String {
    val url = rawUrl.trim()
    if (url.isEmpty()) {
        return url
    }

    return if (url.hasExplicitScheme()) {
        url
    } else {
        "https://$url"
    }
}

private fun String.hasExplicitScheme(): Boolean =
    EXPLICIT_SCHEME_REGEX.matches(this)

private val EXPLICIT_SCHEME_REGEX =
    "^[a-zA-Z][a-zA-Z0-9+.-]*:.*".toRegex()

private val URL_SCHEME_REGEX =
    "^[a-zA-Z]+://.*|^mailto:.*".toRegex()

private enum class ClassifyInputResult {
    URL,
    SEARCH
}

private fun classifyInput(
    rawInput: DSecret.Uri,
): ClassifyInputResult {
    when (rawInput.match) {
        null,
        DSecret.Uri.MatchType.Domain,
        DSecret.Uri.MatchType.Host,
        DSecret.Uri.MatchType.StartsWith,
        DSecret.Uri.MatchType.Exact,
        DSecret.Uri.MatchType.Never -> {
            // Do nothing
        }
        DSecret.Uri.MatchType.RegularExpression -> {
            return ClassifyInputResult.SEARCH
        }
    }

    return classifyInput(rawInput = rawInput.uri)
}

private fun classifyInput(
    rawInput: String,
): ClassifyInputResult {
    val input = rawInput.trim()

    // 1. Empty Input
    if (input.isEmpty()) {
        return ClassifyInputResult.SEARCH
    }

    // 2. Forced Query
    if (input.startsWith("?")) {
        return ClassifyInputResult.SEARCH
    }

    // 3. Explicit Scheme (e.g., http://, chrome://, mailto:)
    if (URL_SCHEME_REGEX.matches(input)) {
        return ClassifyInputResult.URL
    }

    // 4. Whitespace Check
    // Statically, if an input lacks a scheme and contains spaces,
    // it is overwhelmingly likely to be a search query.
    if (input.contains(" ")) {
        return ClassifyInputResult.SEARCH
    }

    // 5. IPv4 Address
    if (REGEX_IPV4.matches(input)) {
        return ClassifyInputResult.URL
    }

    // 6. IPv6 Address
    // Matches bracketed hex formats, optional port, optional path
    val ipv6Regex = "^\\[[a-fA-F0-9:]+\\](?::[0-9]{1,5})?(?:/.*)?$".toRegex()
    if (ipv6Regex.matches(input)) {
        return ClassifyInputResult.URL
    }

    // 7. Localhost Special Case
    // Browsers statically recognize localhost as a navigation target
    if (
        input == "localhost" ||
        input.startsWith("localhost:") ||
        input.startsWith("localhost/")
    ) {
        return ClassifyInputResult.URL
    }

    // 8. Explicit Port Numbers
    val portRegex = "^[a-zA-Z0-9.-]+:[0-9]{1,5}(?:/.*)?$".toRegex()
    if (portRegex.matches(input)) {
        return ClassifyInputResult.URL
    }

    // 9. Top-Level Domains (TLD) Check
    val domainRegex = "^[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*\\.[a-zA-Z]{2,}(?:/.*)?$".toRegex()
    if (domainRegex.matches(input)) {
        return ClassifyInputResult.URL
    }

    // 10. Trailing Slash / Path Indication
    // E.g., "devserver/" forces a navigation target rather than a search
    if (input.contains("/") && !input.startsWith("/")) {
        return ClassifyInputResult.URL
    }

    // 11. Fallback: Single words or ambiguous text
    return ClassifyInputResult.SEARCH
}
