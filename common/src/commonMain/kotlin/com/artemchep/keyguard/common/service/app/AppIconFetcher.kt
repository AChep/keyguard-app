package com.artemchep.keyguard.common.service.app

import coil3.ImageLoader
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.app.parser.AndroidAppGooglePlayParser
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import kotlinx.coroutines.flow.first

/**
 * A [Fetcher] that loads an application's icon as a [coil3.Image].
 *
 * @param data The [AppIconUrl] containing the package name of the application.
 * @param options The [coil3.request.Options] for the request.
 */
class AppIconFetcher(
    private val googlePlayParser: AndroidAppGooglePlayParser,
    private val getWebsiteIcons: GetWebsiteIcons,
    private val data: AppIconUrl,
    private val imageLoader: ImageLoader,
    private val options: coil3.request.Options,
) : Fetcher {
    companion object {
        suspend fun fetch(
            googlePlayParser: AndroidAppGooglePlayParser,
            data: AppIconUrl,
            imageLoader: ImageLoader,
            options: coil3.request.Options,
        ): FetchResult? {
            val appInfo = googlePlayParser(data.packageName)
                .bind()
            val imageUrl = appInfo?.iconUrl
                ?: return null

            // This will delegate to the internal network fetcher.
            val data = imageLoader.components.map(imageUrl, options)
            val output = imageLoader.components.newFetcher(data, options, imageLoader)
            val (fetcher) = checkNotNull(output) { "no supported fetcher" }
            return fetcher.fetch()
        }
    }

    override suspend fun fetch(): FetchResult? {
        val websiteIcons = getWebsiteIcons().first()
        if (!websiteIcons) return null

        return fetch(
            googlePlayParser = googlePlayParser,
            data = data,
            imageLoader = imageLoader,
            options = options,
        )
    }

    /**
     * A [Fetcher.Factory] for creating [AppIconFetcher] instances.
     */
    class Factory(
        private val googlePlayParser: AndroidAppGooglePlayParser,
        private val getWebsiteIcons: GetWebsiteIcons,
    ) : Fetcher.Factory<AppIconUrl> {
        /**
         * Creates an [AppIconFetcher] for the given [AppIconUrl].
         *
         * @param data The [AppIconUrl] containing the package name of the application.
         * @param options The [coil3.request.Options] for the request.
         * @param imageLoader The [ImageLoader] for the request.
         * @return An [AppIconFetcher] instance.
         */
        override fun create(
            data: AppIconUrl,
            options: coil3.request.Options,
            imageLoader: ImageLoader,
        ): Fetcher {
            return AppIconFetcher(
                googlePlayParser = googlePlayParser,
                getWebsiteIcons = getWebsiteIcons,
                data = data,
                imageLoader = imageLoader,
                options = options,
            )
        }
    }
}
