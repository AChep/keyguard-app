package com.artemchep.keyguard.android.glide.website

import android.net.Uri
import com.artemchep.keyguard.android.glide.util.combineModelLoaders
import com.artemchep.keyguard.feature.favicon.Favicon
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import net.mm2d.touchicon.IconComparator
import net.mm2d.touchicon.TouchIconExtractor
import java.io.InputStream

class FaviconLoader : ModelLoader<FaviconUrl, Uri> {
    class Factory : ModelLoaderFactory<FaviconUrl, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory) = kotlin.run {
            val aModelLoader = FaviconLoader()
            val bModelLoader = multiFactory.build(Uri::class.java, InputStream::class.java)
            combineModelLoaders(
                aModelLoader,
                bModelLoader,
            )
        }

        override fun teardown() {
            // Do nothing.
        }
    }

    private val extractor = TouchIconExtractor()

    override fun buildLoadData(
        model: FaviconUrl,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<Uri>? {
        return ModelLoader.LoadData(
            ObjectKey(model.url),
            Fetcher(
                model = model,
                extractor = extractor,
            ),
        )
    }

    override fun handles(model: FaviconUrl): Boolean = true

    class Fetcher(
        private val model: FaviconUrl,
        private val extractor: TouchIconExtractor,
    ) : DataFetcher<Uri> {
        override fun loadData(
            priority: Priority,
            callback: DataFetcher.DataCallback<in Uri>,
        ) {
            kotlin.runCatching {
                val siteUrl = model.url
                val finalUrl = kotlin.run {
                    val server = Favicon.getServerOrNull(model.serverId)
                        ?: Favicon.servers.firstOrNull()
                        ?: return@run extractor
                            .fromPage(
                                siteUrl = siteUrl,
                                withManifest = true,
                            )
                            .maxOfWithOrNull(IconComparator.SIZE) { it }
                            ?.url
                    server.transform(siteUrl)
                }
                finalUrl?.let { Uri.parse(it) }
            }.fold(
                onFailure = { e ->
                    if (e is Exception) {
                        callback.onLoadFailed(e)
                    } else {
                        callback.onLoadFailed(IllegalStateException(e))
                    }
                },
                onSuccess = { imageUrl ->
                    if (imageUrl != null) {
                        callback.onDataReady(imageUrl)
                    } else {
                        callback.onLoadFailed(NullPointerException())
                    }
                },
            )
        }

        override fun cleanup() {
        }

        override fun cancel() {
        }

        override fun getDataClass(): Class<Uri> = Uri::class.java

        override fun getDataSource(): DataSource = DataSource.REMOTE
    }
}
