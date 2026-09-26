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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.koin.core.Koin
import org.koin.core.scope.Scope
import org.koin.dsl.module

/** Coil loaders are cached per platform context, matching their resource ownership. */
class ImageLoaderFactory(private val create: (PlatformContext) -> ImageLoader) {
    private val lock = SynchronizedObject()
    private val loaders = mutableMapOf<PlatformContext, ImageLoader>()

    fun get(context: PlatformContext): ImageLoader = synchronized(lock) {
        loaders.getOrPut(context) { create(context) }
    }
}

class ImageLoadingModule(
    builder: ComponentRegistry.Builder.(scope: Scope) -> Unit,
) {
    val module = module {
        single<ImageLoaderFactory> {
            val scope = this
            val httpClient = get<HttpClient>()
            ImageLoaderFactory { context ->
                ImageLoader.Builder(context)
                    .components {
                        add(KtorNetworkFetcherFactory(httpClient = { httpClient }))
                        installFaviconUrlFetcherFactory(httpClient = { httpClient })
                        add(FaviconUrlKeyer())
                        add(GravatarUrlMapper())
                        add(PictureUrlMapper())
                        builder(scope)
                    }
                    .installPlatformDiskCache(scope)
                    .crossfade(true)
                    .build()
            }
        }
    }
}

fun SingletonImageLoader.setFromKoin(koin: Koin) {
    setSafe { context -> koin.get<ImageLoaderFactory>().get(context) }
}

internal expect fun ComponentRegistry.Builder.installFaviconUrlFetcherFactory(
    httpClient: () -> HttpClient,
)

internal expect fun ImageLoader.Builder.installPlatformDiskCache(
    scope: Scope,
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
