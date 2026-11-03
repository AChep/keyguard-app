package com.artemchep.keyguard.common.service.icon

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.feature.auth.common.util.verifyIsLocalUrl
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json

/**
 * Finds the icon of a website by reading the icons its home page
 * declares. The result is remembered per website, so a vault with many
 * pages of one site reads that site once.
 */
class FaviconScraper(
    private val httpClient: HttpClient,
    private val json: Json,
    private val clock: Clock = Clock.System,
    private val failureTtl: Duration = 10.minutes,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private class Entry(
        val url: Url?,
        val failedAt: Instant?,
    )

    private val lock = SynchronizedObject()

    // Insertion-ordered; a hit moves the entry to the end,
    // so the first entry is the least recently used one.
    private val cache = LinkedHashMap<String, Entry>()

    /**
     * Returns the icon URL of the website at [origin], or
     * `null` if the website could not be reached.
     */
    // Any failure to reach the website is remembered as such for a
    // while; cancellation is rethrown first.
    @Suppress("TooGenericExceptionCaught")
    suspend fun find(origin: Url): Url? {
        val key = origin.toString()
        val cached = get(key)
        if (cached != null) {
            return cached.url
        }

        val entry = try {
            Entry(
                url = scrape(origin),
                failedAt = null,
            )
        } catch (e: Exception) {
            e.throwIfFatalOrCancellation()
            Entry(
                url = null,
                failedAt = clock.now(),
            )
        }
        put(key, entry)
        return entry.url
    }

    private suspend fun scrape(origin: Url): Url? {
        val siteIsLocal = verifyIsLocalUrl(origin)
        val pageUrl = origin.resolveOrNull("/")
            ?: return null
        val page = try {
            // The icons are in the head, at the start of the page.
            val response = httpClient.readIconResponse(
                url = pageUrl,
                maxBytes = MAX_HTML_BYTES,
                accept = "text/html",
                truncate = true,
            )
            parseFaviconPage(
                html = response.bytes.decodeToString(),
                pageUrl = response.url,
            )
        } catch (_: HttpException) {
            // The website does not let us read the page, but
            // it may still serve the conventional icon.
            null
        } catch (_: IconRedirectToLocalUrlException) {
            null
        }

        val candidates = page?.candidates.orEmpty()
        val manifestCandidates = page?.manifestUrl
            ?.takeIf { candidates.none { it.effectiveSize() >= GOOD_ENOUGH_SIZE } }
            ?.takeIf { isFetchableIconUrl(it, siteIsLocal = siteIsLocal) }
            ?.let { manifestUrl -> readManifest(manifestUrl) }
            .orEmpty()
        return pickFavicon(
            candidates = candidates + manifestCandidates,
            origin = origin,
            siteIsLocal = siteIsLocal,
        )
    }

    // The manifest is optional: any failure to read it means it has no icons;
    // cancellation is rethrown first.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun readManifest(manifestUrl: Url): List<FaviconCandidate> = try {
        val response = httpClient.readIconResponse(
            url = manifestUrl,
            maxBytes = MAX_MANIFEST_BYTES,
            accept = "application/manifest+json, application/json",
        )
        parseFaviconManifest(
            json = json,
            text = response.bytes.decodeToString(),
            manifestUrl = response.url,
        )
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        emptyList()
    }

    private fun get(key: String): Entry? = synchronized(lock) {
        val entry = cache.remove(key)
            ?: return@synchronized null
        val failedAt = entry.failedAt
        if (failedAt != null && clock.now() - failedAt >= failureTtl) {
            return@synchronized null
        }
        cache[key] = entry
        entry
    }

    private fun put(key: String, entry: Entry) = synchronized(lock) {
        cache.remove(key)
        cache[key] = entry
        val iterator = cache.entries.iterator()
        while (cache.size > capacity && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 512
        const val MAX_HTML_BYTES = 256L * 1024L
        const val MAX_MANIFEST_BYTES = 64L * 1024L

        /** No need to read the manifest if the page has an icon this large. */
        const val GOOD_ENOUGH_SIZE = 96
    }
}
