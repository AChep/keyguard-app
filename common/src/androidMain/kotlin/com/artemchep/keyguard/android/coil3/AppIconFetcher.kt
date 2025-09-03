package com.artemchep.keyguard.android.coil3

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import coil3.ImageLoader
import coil3.asImage
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable

/**
 * A [Fetcher] that loads an application's icon as a [coil3.Image].
 *
 * @param packageManager The [PackageManager] used to load the application icon.
 * @param data The [AppIconUrl] containing the package name of the application.
 * @param options The [coil3.request.Options] for the request.
 */
class AppIconFetcher(
    private val packageManager: PackageManager,
    private val data: AppIconUrl,
    private val options: coil3.request.Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val icon = getApplicationIconOrThrow(
            packageManager = packageManager,
            packageName = data.packageName,
        )
            ?: return null

        val bitmap = createBitmap(icon.intrinsicWidth, icon.intrinsicHeight)
        val canvas = Canvas(bitmap)
        icon.setBounds(0, 0, icon.intrinsicWidth, icon.intrinsicHeight)
        icon.draw(canvas)

        return ImageFetchResult(
            image = bitmap.toDrawable(options.context).asImage(),
            isSampled = false,
            dataSource = coil3.decode.DataSource.DISK,
        )
    }

    /**
     * A [Fetcher.Factory] for creating [AppIconFetcher] instances.
     *
     * @param packageManager The [PackageManager] used to load application icons.
     */
    class Factory(
        private val packageManager: PackageManager,
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
                packageManager = packageManager,
                data = data,
                options = options,
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
    val appIcon = try {
        packageManager.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        throw e
    }

    return appIcon
}

/**
 * Converts a [Bitmap] to a [Drawable].
 *
 * @param context The [Context] used to access resources.
 * @return The [Drawable] representation of the [Bitmap].
 */
private fun Bitmap.toDrawable(context: Context) = toDrawable(context.resources)

