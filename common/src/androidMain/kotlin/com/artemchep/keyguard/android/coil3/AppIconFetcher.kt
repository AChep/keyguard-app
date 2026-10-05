package com.artemchep.keyguard.android.coil3

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import coil3.ImageLoader
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.common.service.app.parser.AndroidAppGooglePlayParser
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import kotlinx.coroutines.flow.first

/**
 * A [Fetcher] that loads an application's icon as a [coil3.Image].
 *
 * @param packageManager The [PackageManager] used to load the application icon.
 * @param data The [AppIconUrl] containing the package name of the application.
 * @param options The [coil3.request.Options] for the request.
 */
class AppIconFetcher(
    private val googlePlayParser: AndroidAppGooglePlayParser,
    private val packageManager: PackageManager,
    private val getWebsiteIcons: GetWebsiteIcons,
    private val data: AppIconUrl,
    private val options: coil3.request.Options,
    private val imageLoader: ImageLoader,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        return fetchInstalledAppIcon()
            ?: fetchGooglePlayStoreAppIcon()
    }

    private suspend fun fetchInstalledAppIcon(): FetchResult? {
        val icon = runCatching {
            getApplicationIconOrThrow(
                packageManager = packageManager,
                packageName = data.packageName,
            )
        }.getOrNull()
            ?: return null

        return icon.toAppIconImage(options)
    }

    private suspend fun fetchGooglePlayStoreAppIcon(): FetchResult? {
        val websiteIcons = getWebsiteIcons().first()
        if (!websiteIcons) return null

        return com.artemchep.keyguard.common.service.app.AppIconFetcher.fetch(
            googlePlayParser = googlePlayParser,
            data = data,
            imageLoader = imageLoader,
            options = options,
        )
    }

    /**
     * A [Fetcher.Factory] for creating [AppIconFetcher] instances.
     *
     * @param packageManager The [PackageManager] used to load application icons.
     */
    class Factory(
        private val googlePlayParser: AndroidAppGooglePlayParser,
        private val packageManager: PackageManager,
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
                packageManager = packageManager,
                getWebsiteIcons = getWebsiteIcons,
                data = data,
                options = options,
                imageLoader = imageLoader,
            )
        }
    }
}

/**
 * Retrieves the application icon [Drawable] for the given package name.
 *
 * @param packageManager The [PackageManager] used to load the application icon.
 * @param packageName The package name of the application.
 * @return The application icon [Drawable], or null if the package name is empty.
 * @throws PackageManager.NameNotFoundException if the application is not found.
 */
private fun getApplicationIconOrThrow(
    packageManager: PackageManager,
    packageName: String,
): Drawable? {
    if (packageName.isEmpty()) {
        // no need to check if empty package is installed
        return null
    }
    return packageManager.getApplicationIcon(packageName)
}
