package com.artemchep.keyguard.android.glide.app

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey

class AppIconLoader(
    private val packageManager: PackageManager,
) : ModelLoader<AppIconUrl, Bitmap> {
    class Factory(
        private val packageManager: PackageManager,
    ) : ModelLoaderFactory<AppIconUrl, Bitmap> {
        override fun build(
            multiFactory: MultiModelLoaderFactory,
        ): ModelLoader<AppIconUrl, Bitmap> = AppIconLoader(
            packageManager = packageManager,
        )

        override fun teardown() {
        }
    }

    override fun buildLoadData(
        model: AppIconUrl,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<Bitmap> {
        val packageName = model.packageName
        return ModelLoader.LoadData(
            ObjectKey(model.packageName),
            Fetcher(
                packageManager = packageManager,
                packageName = packageName,
            ),
        )
    }

    class Fetcher(
        private val packageManager: PackageManager,
        private val packageName: String,
    ) : DataFetcher<Bitmap> {
        override fun loadData(
            priority: Priority,
            callback: DataFetcher.DataCallback<in Bitmap>,
        ) {
            val icon = getApplicationIcon(packageName)
            if (icon != null) {
                val bitmap = Bitmap.createBitmap(
                    icon.intrinsicWidth,
                    icon.intrinsicHeight,
                    Bitmap.Config.ARGB_8888,
                )
                val canvas = Canvas(bitmap)
                icon.setBounds(0, 0, icon.intrinsicWidth, icon.intrinsicHeight)
                icon.draw(canvas)
                callback.onDataReady(bitmap)
            } else {
                callback.onLoadFailed(RuntimeException())
            }
        }

        private fun getApplicationIcon(packageName: String): Drawable? {
            if (packageName.isEmpty()) {
                // no need to check if empty package is installed
                return null
            }
            val appIcon = try {
                packageManager.getApplicationIcon(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                return null
            }

            return appIcon
        }

        override fun cleanup() {
        }

        override fun cancel() {
        }

        override fun getDataClass(): Class<Bitmap> = Bitmap::class.java

        override fun getDataSource(): DataSource = DataSource.LOCAL
    }

    override fun handles(model: AppIconUrl): Boolean = true
}
