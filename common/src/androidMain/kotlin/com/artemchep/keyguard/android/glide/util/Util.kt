package com.artemchep.keyguard.android.glide.util

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader

inline fun <In : Any, T : Any, reified Out> combineModelLoaders(
    a: ModelLoader<In, T>,
    b: ModelLoader<T, Out>,
): ModelLoader<In, Out> = object : ModelLoader<In, Out> {
    override fun buildLoadData(
        model: In,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<Out>? {
        val aFetcher = a.buildLoadData(model, width, height, options)
            ?: return null
        val bFetcher = object : DataFetcher<Out> {
            private var z: DataFetcher<Out>? = null

            override fun loadData(
                priority: Priority,
                callback: DataFetcher.DataCallback<in Out>,
            ) {
                aFetcher.fetcher.loadData(
                    priority,
                    object : DataFetcher.DataCallback<T> {
                        override fun onDataReady(data: T?) {
                            val f = b.buildLoadData(data!!, width, height, options)
                            if (f != null) {
                                z = f.fetcher
                                f.fetcher.loadData(priority, callback)
                            } else {
                                callback.onLoadFailed(IllegalStateException())
                            }
                        }

                        override fun onLoadFailed(e: Exception) {
                            callback.onLoadFailed(e)
                        }
                    },
                )
            }

            override fun cleanup() {
                z?.cleanup()
            }

            override fun cancel() {
                z?.cancel()
            }

            override fun getDataClass(): Class<Out> = Out::class.java

            override fun getDataSource(): DataSource = aFetcher.fetcher.dataSource
        }
        return ModelLoader.LoadData(
            aFetcher.sourceKey,
            bFetcher,
        )
    }

    override fun handles(model: In): Boolean = a.handles(model)
}
