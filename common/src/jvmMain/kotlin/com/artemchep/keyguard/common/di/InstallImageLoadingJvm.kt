package com.artemchep.keyguard.common.di

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.Options
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.feature.favicon.Favicon
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.discardRemaining
import io.ktor.client.statement.readBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import net.mm2d.touchicon.IconComparator
import net.mm2d.touchicon.TouchIconExtractor
import net.mm2d.touchicon.http.HttpClientAdapter
import net.mm2d.touchicon.http.HttpResponse
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.File
import java.io.IOException

internal actual fun ComponentRegistry.Builder.installFaviconUrlFetcherFactory(
    httpClient: () -> HttpClient,
) {
    add(
        FaviconUrlFetcher.Factory(
            httpClient = httpClient,
        ),
    )
}

internal actual fun ImageLoader.Builder.installPlatformDiskCache(
    directDI: DirectDI,
): ImageLoader.Builder =
    diskCache {
        // This is getting called from a default dispatcher, according
        // to the coil documentation.
        val cacheDirProvider = directDI.instance<CacheDirProvider>()
        val cacheDir = cacheDirProvider.getBlocking()
        DiskCache.Builder()
            .directory(File(cacheDir.value).resolve("coil3_disk_cache"))
            .build()
    }

private class FaviconUrlFetcher(
    private val data: FaviconUrl,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val extractor: TouchIconExtractor,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val imageUrl = getActualFaviconImageUrl(data)
        requireNotNull(imageUrl) {
            "Could not detect a favicon"
        }

        // This will delegate to the internal network fetcher.
        val data = imageLoader.components.map(imageUrl, options)
        val output = imageLoader.components.newFetcher(data, options, imageLoader)
        val (fetcher) = checkNotNull(output) { "no supported fetcher" }
        return fetcher.fetch()
    }

    private suspend fun getActualFaviconImageUrl(data: FaviconUrl): String? {
        val server = Favicon.getServerOrNull(data.serverId)
        if (server != null) {
            return server.transform(data.url)
        }

        val imageUrl = extractor
            .fromPage(
                siteUrl = data.url,
                withManifest = true,
            )
            .maxOfWithOrNull(IconComparator.SIZE) { it }
            ?.url
        return imageUrl
    }

    class Factory(
        private val httpClient: () -> HttpClient,
    ) : Fetcher.Factory<FaviconUrl> {
        override fun create(data: FaviconUrl, options: Options, imageLoader: ImageLoader): Fetcher {
            val extractor = TouchIconExtractor(
                httpClient = KtorHttpClientAdapter(client = httpClient()),
            )
            return FaviconUrlFetcher(data, options, imageLoader, extractor)
        }
    }

    internal class KtorHttpClientAdapter(
        private val client: HttpClient,
    ) : HttpClientAdapter {
        override var userAgent: String = ""
        override var headers: Map<String, String> = emptyMap()

        @Throws(IOException::class)
        override fun head(
            url: String,
        ): HttpResponse = runBlocking {
            client.head(urlString = url)
                .let(::KtorHttpResponse)
        }

        @Throws(IOException::class)
        override fun get(
            url: String,
        ): HttpResponse = runBlocking {
            client.get(urlString = url)
                .let(::KtorHttpResponse)
        }
    }

    internal class KtorHttpResponse(
        private val response: io.ktor.client.statement.HttpResponse,
    ) : HttpResponse {
        override val isSuccess: Boolean
            get() = response.status.isSuccess()

        override fun header(
            name: String,
        ): String? = response.headers[name]

        @Throws(IOException::class)
        override fun bodyString(
            limit: Int,
        ): String? = runBlocking {
            if (limit <= 0) {
                response.bodyAsText()
            } else {
                response.readBytes(limit)
                    .let(::String)
            }
        }

        @Throws(IOException::class)
        override fun bodyBytes(
            limit: Int,
        ): ByteArray? = runBlocking {
            if (limit <= 0) {
                response.bodyAsBytes()
            } else {
                response.readBytes(limit)
            }
        }

        override fun close() {
            runBlocking {
                response.discardRemaining()
            }
        }
    }
}
