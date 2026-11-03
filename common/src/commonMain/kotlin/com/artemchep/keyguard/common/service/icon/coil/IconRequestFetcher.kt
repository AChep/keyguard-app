@file:OptIn(ExperimentalCoilApi::class)

package com.artemchep.keyguard.common.service.icon.coil

import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.network.ConcurrentRequestStrategy
import coil3.network.NetworkClient
import coil3.network.NetworkFetcher
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.ktor3.asNetworkClient
import coil3.request.Options
import coil3.toUri
import com.artemchep.keyguard.common.service.icon.IconResolver
import com.artemchep.keyguard.common.service.icon.faviconOrigin
import com.artemchep.keyguard.common.service.icon.parseHttpUrlOrNull
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.favicon.IconRequest
import com.artemchep.keyguard.feature.favicon.IosAppIconUrl
import com.artemchep.keyguard.feature.favicon.RemoteIconUrl
import com.artemchep.keyguard.feature.favicon.toIconKey

class IconDisabledException : IllegalStateException("The icon settings do not allow this icon.")

class IconUnavailableException : IllegalStateException("The icon is not available.")

/**
 * Loads an [IconRequest] through Coil's network fetcher. The image is
 * cached under a key of the request, so the icon is resolved, which
 * may mean reading the website, only if it is not on the disk yet.
 */
class IconRequestFetcher(
    private val request: IconRequest,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val resolver: IconResolver,
    private val concurrentRequestStrategy: ConcurrentRequestStrategy,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        // Checked before reading the disk cache, so turning the
        // setting off hides the icons that were downloaded before.
        if (!resolver.isAllowed(request)) {
            throw IconDisabledException()
        }
        val networkClient = ResolvingNetworkClient(
            resolver = resolver,
            request = request,
        )
        val fetcher = request.placeholderUrl()
            ?.let { url ->
                NetworkFetcher
                    .Factory(
                        networkClient = { networkClient },
                        concurrentRequestStrategy = { concurrentRequestStrategy },
                    )
                    .create(
                        data = url.toUri(),
                        options = options.copy(diskCacheKey = request.toIconKey()),
                        imageLoader = imageLoader,
                    )
            }
            ?: throw IconUnavailableException()
        return fetcher.fetch()
    }

    class Factory<T : IconRequest>(
        private val resolver: IconResolver,
        private val concurrentRequestStrategy: ConcurrentRequestStrategy,
    ) : Fetcher.Factory<T> {
        override fun create(
            data: T,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = IconRequestFetcher(
            request = data,
            options = options,
            imageLoader = imageLoader,
            resolver = resolver,
            concurrentRequestStrategy = concurrentRequestStrategy,
        )
    }
}

/**
 * Resolves the icon only when the network fetcher has to download it.
 * The fetcher has checked the icon settings before reading the disk.
 */
private class ResolvingNetworkClient(
    private val resolver: IconResolver,
    private val request: IconRequest,
) : NetworkClient {
    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (response: NetworkResponse) -> T,
    ): T {
        val icon = resolver.resolveAllowed(this.request)
            ?: throw IconUnavailableException()
        return icon.request { httpClient, url ->
            httpClient
                .asNetworkClient()
                .executeRequest(request.copy(url = url), block)
        }
    }
}

/**
 * A http(s) URL that stands for the icon until it is resolved;
 * Coil's network fetcher only accepts those.
 */
private fun IconRequest.placeholderUrl(): String? = when (this) {
    is FaviconUrl -> faviconOrigin(url)?.toString()
    is AppIconUrl -> "https://play.google.com/store/apps/details?id=$packageName"
    is IosAppIconUrl -> "https://itunes.apple.com/lookup?bundleId=$bundleId"
    is RemoteIconUrl -> parseHttpUrlOrNull(url)?.toString()
}
