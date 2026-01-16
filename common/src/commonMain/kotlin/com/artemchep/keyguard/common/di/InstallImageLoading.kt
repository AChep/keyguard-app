package com.artemchep.keyguard.common.di

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.Options
import coil3.request.crossfade
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.feature.favicon.Favicon
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.favicon.GravatarUrl
import com.artemchep.keyguard.feature.favicon.PictureUrl
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
import org.kodein.di.DI
import org.kodein.di.DirectDI
import org.kodein.di.bindMultiton
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.IOException

fun imageLoaderModule(
    builder: ComponentRegistry.Builder.(directDI: DirectDI) -> Unit,
) = DI.Module(
    name = "imageLoader",
) {
    bindMultiton<PlatformContext, ImageLoader> { context: PlatformContext ->
        ImageLoader.Builder(context)
            .components {
                val ktorFetcherFactory = KtorNetworkFetcherFactory(
                    httpClient = {
                        di.direct.instance<HttpClient>()
                    },
                )
                add(ktorFetcherFactory)

                // Extending the image pipeline
                val faviconFetcherFactory = FaviconUrlFetcher.Factory(
                    httpClient = {
                        di.direct.instance<HttpClient>()
                    },
                )
                add(faviconFetcherFactory)
                add(FaviconUrlKeyer())
                add(GravatarUrlMapper())
                add(PictureUrlMapper())

                // Platform specific
                builder(directDI)
            }
            .diskCache {
                // This is getting called from a default dispatcher, according
                // to the coil documentation.
                val cacheDirProvider = di.direct.instance<CacheDirProvider>()
                val cacheDir = cacheDirProvider.getBlocking()
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil3_disk_cache"))
                    .build()
            }
            .crossfade(true)
            .build()
    }
}

fun SingletonImageLoader.setFromDi(di: DI) {
    setSafe { context ->
        di.direct.instance<PlatformContext, ImageLoader>(arg = context)
    }
}

class FaviconUrlFetcher(
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

    // Touch Icon Extractor

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

class FaviconUrlKeyer : Keyer<FaviconUrl> {
    override fun key(
        data: FaviconUrl,
        options: Options,
    ): String? {
        return data.serverId + "|" + data.url
    }
}

internal class GravatarUrlMapper : Mapper<GravatarUrl, String> {
    override fun map(
        data: GravatarUrl,
        options: Options,
    ): String? {
        return data.url
    }
}

internal class PictureUrlMapper : Mapper<PictureUrl, String> {
    override fun map(
        data: PictureUrl,
        options: Options,
    ): String? {
        return data.url
    }
}
