package com.artemchep.keyguard.common.di

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.Options
import coil3.request.crossfade
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.favicon.GravatarUrl
import com.artemchep.keyguard.feature.favicon.PictureUrl
import io.ktor.client.HttpClient
import org.kodein.di.DI
import org.kodein.di.DirectDI
import org.kodein.di.bindMultiton
import org.kodein.di.direct
import org.kodein.di.instance

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
                installFaviconUrlFetcherFactory(
                    httpClient = {
                        di.direct.instance<HttpClient>()
                    },
                )
                add(FaviconUrlKeyer())
                add(GravatarUrlMapper())
                add(PictureUrlMapper())

                // Platform specific
                builder(directDI)
            }
            .installPlatformDiskCache(directDI)
            .crossfade(true)
            .build()
    }
}

fun SingletonImageLoader.setFromDi(di: DI) {
    setSafe { context ->
        di.direct.instance<PlatformContext, ImageLoader>(arg = context)
    }
}

internal expect fun ComponentRegistry.Builder.installFaviconUrlFetcherFactory(
    httpClient: () -> HttpClient,
)

internal expect fun ImageLoader.Builder.installPlatformDiskCache(
    directDI: DirectDI,
): ImageLoader.Builder

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
