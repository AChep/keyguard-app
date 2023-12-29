package com.artemchep.keyguard.android.glide.qr

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.asAndroidBitmap
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.common.usecase.GetBarcodeImage
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey

class QrLoader(
    private val context: Context,
    private val getBarcodeImage: GetBarcodeImage,
) : ModelLoader<BarcodeImageRequest, Drawable> {
    class Factory(
        private val context: Context,
        private val getBarcodeImage: GetBarcodeImage,
    ) : ModelLoaderFactory<BarcodeImageRequest, Drawable> {
        override fun build(
            multiFactory: MultiModelLoaderFactory,
        ): ModelLoader<BarcodeImageRequest, Drawable> = QrLoader(
            context = context,
            getBarcodeImage = getBarcodeImage,
        )

        override fun teardown() {
        }
    }

    override fun buildLoadData(
        model: BarcodeImageRequest,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<Drawable> {
        val newSize = model.size ?: BarcodeImageRequest.Size(
            width = width,
            height = height,
        )
        val newModel = model.copy(size = newSize)
        return ModelLoader.LoadData(
            ObjectKey(model),
            Fetcher(
                context = context,
                getBarcodeImage = getBarcodeImage,
                model = newModel,
            ),
        )
    }

    class Fetcher(
        private val context: Context,
        private val getBarcodeImage: GetBarcodeImage,
        private val model: BarcodeImageRequest,
    ) : DataFetcher<Drawable> {
        override fun loadData(
            priority: Priority,
            callback: DataFetcher.DataCallback<in Drawable>,
        ) {
            getBarcodeImage(model)
                .attempt()
                .bindBlocking()
                .fold(
                    ifLeft = {
                        val e = it as? Exception ?: RuntimeException(it)
                        callback.onLoadFailed(e)
                    },
                    ifRight = { bitmap ->
                        val drawable = BitmapDrawable(context.resources, bitmap.asAndroidBitmap())
                        callback.onDataReady(drawable)
                    },
                )
        }

        override fun cleanup() {
        }

        override fun cancel() {
        }

        override fun getDataClass(): Class<Drawable> = Drawable::class.java

        override fun getDataSource(): DataSource = DataSource.MEMORY_CACHE
    }

    override fun handles(model: BarcodeImageRequest): Boolean = true
}
