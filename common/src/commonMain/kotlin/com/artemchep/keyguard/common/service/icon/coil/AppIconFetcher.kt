package com.artemchep.keyguard.common.service.icon.coil

import coil3.ImageLoader
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.Options
import com.artemchep.keyguard.feature.favicon.AppIconUrl

/**
 * Loads the icon of an installed app, if the platform has one, and
 * otherwise the icon from the app's store listing.
 */
class AppIconFetcher(
    private val data: AppIconUrl,
    private val options: Options,
    private val storeFetcher: Fetcher,
) : Fetcher {
    override suspend fun fetch(): FetchResult? =
        installedAppIconOrNull(data.packageName, options)
            ?: storeFetcher.fetch()

    class Factory(
        private val storeFactory: IconRequestFetcher.Factory<AppIconUrl>,
    ) : Fetcher.Factory<AppIconUrl> {
        override fun create(
            data: AppIconUrl,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = AppIconFetcher(
            data = data,
            options = options,
            storeFetcher = storeFactory.create(data, options, imageLoader),
        )
    }
}

/** The icon of an installed app; it is local, so no setting gates it. */
internal expect suspend fun installedAppIconOrNull(
    packageName: String,
    options: Options,
): FetchResult?
