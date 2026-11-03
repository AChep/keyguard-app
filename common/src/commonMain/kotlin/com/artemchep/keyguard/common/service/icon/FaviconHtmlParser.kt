package com.artemchep.keyguard.common.service.icon

import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class FaviconCandidate(
    val url: Url,
    /** The largest declared edge in pixels, or `null` if unknown. */
    val size: Int?,
    val type: Type,
) {
    enum class Type {
        ICON,
        APPLE_TOUCH_ICON,
        MANIFEST,
    }
}

data class FaviconPage(
    val candidates: List<FaviconCandidate>,
    val manifestUrl: Url?,
)

/**
 * Collects the icons a page declares in its `<head>`. Relative links
 * resolve against the `<base>` element, or the [pageUrl] without one.
 */
fun parseFaviconPage(
    html: String,
    pageUrl: Url,
): FaviconPage {
    val head = parseHead(html, pageUrl)
    val manifestUrl = head.links
        .firstOrNull { "manifest" in it.rel }
        ?.let { head.baseUrl.resolveOrNull(it.href) }
    return FaviconPage(
        candidates = head.links.mapNotNull { it.toFaviconCandidate(head.baseUrl) },
        manifestUrl = manifestUrl,
    )
}

private class HeadLink(
    val rel: List<String>,
    val href: String,
    val type: String?,
    val sizes: String?,
)

private class Head(
    val baseUrl: Url,
    val links: List<HeadLink>,
)

private fun parseHead(
    html: String,
    pageUrl: Url,
): Head {
    var baseUrl = pageUrl
    var inBody = false
    val links = mutableListOf<HeadLink>()

    val handler = KsoupHtmlHandler.Builder()
        .onOpenTag { name, attributes, _ ->
            when {
                inBody -> Unit
                name.equals("body", ignoreCase = true) -> inBody = true
                name.equals("base", ignoreCase = true) -> {
                    attributes["href"]
                        ?.let { pageUrl.resolveOrNull(it) }
                        ?.let { baseUrl = it }
                }

                name.equals("link", ignoreCase = true) -> {
                    val href = attributes["href"]?.trim().orEmpty()
                    if (href.isNotEmpty()) {
                        links += HeadLink(
                            rel = attributes["rel"]
                                ?.lowercase()
                                ?.split(' ', '\t', '\n', '\r')
                                .orEmpty(),
                            href = href,
                            type = attributes["type"],
                            sizes = attributes["sizes"],
                        )
                    }
                }
            }
        }
        .onCloseTag { name, _ ->
            if (name.equals("head", ignoreCase = true)) {
                inBody = true
            }
        }
        .build()
    val parser = KsoupHtmlParser(handler = handler)
    parser.write(html)
    parser.end()
    return Head(
        baseUrl = baseUrl,
        links = links,
    )
}

private fun HeadLink.toFaviconCandidate(baseUrl: Url): FaviconCandidate? {
    val candidateType = when {
        "apple-touch-icon" in rel ||
                "apple-touch-icon-precomposed" in rel -> FaviconCandidate.Type.APPLE_TOUCH_ICON

        "icon" in rel -> FaviconCandidate.Type.ICON
        else -> null
    }
    val url = baseUrl.resolveOrNull(href)
    if (candidateType == null || url == null || type.isSvgType()) {
        return null
    }
    return FaviconCandidate(
        url = url,
        size = parseIconSize(sizes),
        type = candidateType,
    )
}

/** Collects the icons of a web app manifest. */
fun parseFaviconManifest(
    json: Json,
    text: String,
    manifestUrl: Url,
): List<FaviconCandidate> {
    val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
    val icons = root?.get("icons") as? JsonArray
        ?: return emptyList()
    return icons.mapNotNull { element ->
        (element as? JsonObject)?.toManifestCandidate(manifestUrl)
    }
}

private fun JsonObject.toManifestCandidate(manifestUrl: Url): FaviconCandidate? {
    // Monochrome icons require additional processing before they can be displayed.
    val purposes = string("purpose")?.split(' ')
    val usable = purposes == null || "any" in purposes || "maskable" in purposes
    val url = string("src")?.let { manifestUrl.resolveOrNull(it) }
    if (!usable || url == null || string("type").isSvgType()) {
        return null
    }
    return FaviconCandidate(
        url = url,
        size = parseIconSize(string("sizes")),
        type = FaviconCandidate.Type.MANIFEST,
    )
}

private fun String?.isSvgType() = this?.contains("svg", ignoreCase = true) == true

/**
 * Picks the largest icon both platforms can decode, falling
 * back to the conventional `/favicon.ico` of the [origin].
 */
fun pickFavicon(
    candidates: List<FaviconCandidate>,
    origin: Url,
    siteIsLocal: Boolean,
): Url? = candidates
    .filter { candidate ->
        isFetchableIconUrl(candidate.url, siteIsLocal = siteIsLocal) &&
                !candidate.url.encodedPath.endsWith(".svg", ignoreCase = true)
    }
    .maxWithOrNull(
        compareBy<FaviconCandidate> { it.effectiveSize() }
            .thenBy { !it.url.encodedPath.endsWith(".ico", ignoreCase = true) },
    )
    ?.url
    ?: origin.resolveOrNull("/favicon.ico")

/** The size to assume when a candidate does not declare one. */
internal fun FaviconCandidate.effectiveSize(): Int = size ?: when (type) {
    FaviconCandidate.Type.APPLE_TOUCH_ICON -> DEFAULT_APPLE_TOUCH_ICON_SIZE
    FaviconCandidate.Type.ICON,
    FaviconCandidate.Type.MANIFEST,
    -> if (url.encodedPath.endsWith(".ico", ignoreCase = true)) {
        DEFAULT_ICO_SIZE
    } else {
        DEFAULT_ICON_SIZE
    }
}

private const val DEFAULT_APPLE_TOUCH_ICON_SIZE = 180
private const val DEFAULT_ICON_SIZE = 32
private const val DEFAULT_ICO_SIZE = 16

private val iconSizeRegex = "^(\\d+)x(\\d+)$".toRegex(RegexOption.IGNORE_CASE)

/** Parses `16x16 32x32`; `any` and garbage give `null`. */
internal fun parseIconSize(sizes: String?): Int? = sizes
    ?.split(' ', '\t', '\n')
    ?.mapNotNull { token ->
        val match = iconSizeRegex.matchEntire(token.trim())
            ?: return@mapNotNull null
        val (width, height) = match.destructured
        maxOf(width.toIntOrNull() ?: 0, height.toIntOrNull() ?: 0)
            .takeIf { it > 0 }
    }
    ?.maxOrNull()

private val schemeRegex = "^[a-zA-Z][a-zA-Z0-9+.-]*:".toRegex()

internal fun Url.resolveOrNull(href: String): Url? {
    val url = runCatching {
        when {
            schemeRegex.containsMatchIn(href) -> parseHttpUrlOrNull(href)
            href.startsWith("//") -> parseHttpUrlOrNull("${protocol.name}:$href")
            else -> URLBuilder(this)
                .apply {
                    // The builder appends to the query
                    // instead of replacing it.
                    encodedParameters.clear()
                    trailingQuery = false
                    encodedFragment = ""
                }
                .takeFrom(href)
                .build()
        }
    }.getOrNull()
        ?: return null
    return url.takeIf { it.isHttp() && it.host.isNotBlank() }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
