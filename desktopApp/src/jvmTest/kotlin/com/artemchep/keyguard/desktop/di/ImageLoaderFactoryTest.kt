package com.artemchep.keyguard.desktop.di

import coil3.ImageLoader
import coil3.PlatformContext
import com.artemchep.keyguard.common.di.ImageLoaderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ImageLoaderFactoryTest {
    @Test
    fun `loader is reused within its factory and remains isolated from other factories`() {
        val created = mutableListOf<ImageLoader>()
        fun factory() = ImageLoaderFactory { context ->
            ImageLoader.Builder(context)
                .memoryCache(null)
                .diskCache(null)
                .build()
                .also(created::add)
        }
        try {
            val firstFactory = factory()
            val secondFactory = factory()
            val first = firstFactory.get(PlatformContext.INSTANCE)

            assertSame(first, firstFactory.get(PlatformContext.INSTANCE))
            assertNotSame(first, secondFactory.get(PlatformContext.INSTANCE))
            assertEquals(2, created.size)
        } finally {
            created.forEach(ImageLoader::shutdown)
        }
    }
}
