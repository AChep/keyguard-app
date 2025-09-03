package com.artemchep.keyguard.common.di

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
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
import org.kodein.di.DI
import org.kodein.di.bindMultiton
import org.kodein.di.direct
import org.kodein.di.instance

fun imageLoaderModule(
    builder: ComponentRegistry.Builder.() -> Unit,
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
                add(FaviconUrlMapper())
                add(GravatarUrlMapper())
                add(PictureUrlMapper())

                // Platform specific
                builder()
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

internal class FaviconUrlMapper : Mapper<FaviconUrl, String> {
    override fun map(
        data: FaviconUrl,
        options: Options,
    ): String? {
        val siteUrl = data.url
        val finalUrl = kotlin.run {
            val server = Favicon.getServerOrNull(data.serverId)
            server?.transform(siteUrl)
        }
        return finalUrl
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
