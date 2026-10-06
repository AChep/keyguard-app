package com.artemchep.keyguard.test.coil3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.view.Gravity
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.bitmapConfig
import coil3.request.maxBitmapSize
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import com.artemchep.keyguard.android.coil3.toAppIconImage
import com.artemchep.keyguard.test.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@SmallTest
class AppIconImageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun rasterIconsRespectFitAndFillWithoutDistortion() {
        val icon = bitmapIcon(512, 256)
        val fit = icon.toAppIconImage(options(Size(72, 72), scale = Scale.FIT))
        val fill = icon.toAppIconImage(options(Size(72, 72), scale = Scale.FILL))

        assertEquals(72, fit.image.width)
        assertEquals(36, fit.image.height)
        assertEquals(144, fill.image.width)
        assertEquals(72, fill.image.height)
        assertTrue(fit.isSampled)
        assertTrue(fill.isSampled)
        assertTrue(fit.image.shareable)
        assertEquals(72 * 36 * 4, (fit.image as BitmapImage).bitmap.byteCount)
    }

    @Test
    fun fullSizeRasterIsNotSampledAndInexactRequestsDoNotUpscaleIt() {
        val icon = bitmapIcon(32, 32)
        val original = icon.toAppIconImage(options(Size.ORIGINAL))
        val inexact = icon.toAppIconImage(options(Size(128, 128)))
        val exact = icon.toAppIconImage(options(Size(128, 128), precision = Precision.EXACT))

        assertEquals(32, original.image.width)
        assertEquals(32, original.image.height)
        assertEquals(32, inexact.image.width)
        assertEquals(32, inexact.image.height)
        assertFalse(original.isSampled)
        assertFalse(inexact.isSampled)
        assertEquals(128, exact.image.width)
        assertEquals(128, exact.image.height)
    }

    @Test
    fun bitmapDrawableTintAndAlphaSurviveResizing() {
        val icon = bitmapIcon(512, 512).apply {
            setTint(Color.RED)
            alpha = 128
        }
        val result = icon.toAppIconImage(options(Size(72, 72)))
        val pixel = (result.image as BitmapImage).bitmap.getPixel(36, 36)

        assertEquals(128, Color.alpha(pixel))
        assertEquals(255, Color.red(pixel))
        assertEquals(0, Color.green(pixel))
        assertEquals(0, Color.blue(pixel))
    }

    @Test
    fun bitmapGravityDoesNotCropResizedIcons() {
        val icon = bitmapIcon(512, 512).apply {
            // Mark the top-left corner, which a centered draw would crop out.
            bitmap.setPixels(IntArray(64 * 64) { Color.RED }, 0, 64, 0, 0, 64, 64)
            gravity = Gravity.CENTER
        }
        val bitmap = (icon.toAppIconImage(options(Size(72, 72))).image as BitmapImage).bitmap

        assertEquals(Color.RED, bitmap.getPixel(0, 0))
        assertEquals(Color.GREEN, bitmap.getPixel(71, 71))
    }

    @Test
    fun requestedBitmapConfigIsUsedWithSoftwareFallback() {
        val icon = bitmapIcon(32, 32)
        val rgb565 = icon.toAppIconImage(options(Size(32, 32), bitmapConfig = Bitmap.Config.RGB_565))
        val hardware = icon.toAppIconImage(options(Size(32, 32), bitmapConfig = Bitmap.Config.HARDWARE))

        assertEquals(Bitmap.Config.RGB_565, (rgb565.image as BitmapImage).bitmap.config)
        assertEquals(Bitmap.Config.ARGB_8888, (hardware.image as BitmapImage).bitmap.config)
    }

    @Test
    fun originalAndPartiallySpecifiedRequestsRespectBitmapLimit() {
        val icon = bitmapIcon(512, 256)
        val limited = icon.toAppIconImage(options(Size.ORIGINAL, maxSize = Size(128, 128)))
        val widthOnly = icon.toAppIconImage(options(Size(Dimension(72), Dimension.Undefined)))

        assertEquals(128, limited.image.width)
        assertEquals(64, limited.image.height)
        assertTrue(limited.isSampled)
        assertEquals(72, widthOnly.image.width)
        assertEquals(36, widthOnly.image.height)
    }

    @Test
    fun drawablesWithoutIntrinsicSizeUseSquareFallback() {
        val icon = ColorDrawable(Color.GREEN)
        val sized = icon.toAppIconImage(options(Size(72, 108)))
        val widthOnly = icon.toAppIconImage(options(Size(Dimension(72), Dimension.Undefined)))
        val original = icon.toAppIconImage(options(Size.ORIGINAL, maxSize = Size(128, 128)))

        assertEquals(72, sized.image.width)
        assertEquals(72, sized.image.height)
        assertEquals(72, widthOnly.image.width)
        assertEquals(72, widthOnly.image.height)
        assertEquals(128, original.image.width)
        assertEquals(128, original.image.height)
        assertTrue(sized.isSampled)
    }

    @Test
    fun veryNarrowDrawablesNeverRoundToZeroPixels() {
        val icon = object : ColorDrawable(Color.GREEN) {
            override fun getIntrinsicWidth() = 1
            override fun getIntrinsicHeight() = 100_000
        }
        val image = icon.toAppIconImage(options(Size(72, 72))).image

        assertEquals(1, image.width)
        assertEquals(72, image.height)
    }

    @Test
    fun vectorRasterizationPreservesTransparencyAndRemainsSizeDependent() {
        val result = vectorIcon().toAppIconImage(options(Size(128, 128)))
        val bitmap = (result.image as BitmapImage).bitmap

        assertEquals(128, bitmap.width)
        assertEquals(128, bitmap.height)
        assertEquals(Color.GREEN, bitmap.getPixel(64, 64))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        assertTrue(result.isSampled)
    }

    @Test
    fun adaptiveIconPreservesMaskAndRestoresDrawableBounds() {
        val icon = adaptiveIcon()
        val originalBounds = Rect(11, 17, 89, 95)
        icon.bounds = originalBounds
        val result = icon.toAppIconImage(options(Size(72, 72)))
        val bitmap = (result.image as BitmapImage).bitmap
        // The mask shape depends on the device, so compare against drawing
        // the icon directly instead of probing for transparent corners.
        val expected = createBitmap(72, 72)
        adaptiveIcon().apply {
            setBounds(0, 0, 72, 72)
            draw(Canvas(expected))
        }

        assertEquals(72, bitmap.width)
        assertEquals(72, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(36, 36))
        assertTrue(expected.sameAs(bitmap))
        assertEquals(originalBounds, icon.bounds)
        assertTrue(result.isSampled)
        assertTrue(result.image.shareable)
    }

    @Test
    fun rasterAdaptiveIconsAreNotUpscaledByInexactRequests() {
        val icon = AdaptiveIconDrawable(
            ColorDrawable(Color.RED),
            bitmapIcon(108, 108),
        )
        val size = icon.intrinsicWidth
        val result = icon.toAppIconImage(options(Size(size * 4, size * 4)))

        assertEquals(size, result.image.width)
        assertEquals(size, result.image.height)
        assertFalse(result.isSampled)
    }

    @Test
    fun cacheRerendersLargerRequestsAndReusesSufficientlyLargeIcons() = runBlocking {
        val iconFactories = listOf(
            { bitmapIcon(512, 512) },
            { vectorIcon() },
            { adaptiveIcon() },
        )
        for (createIcon in iconFactories) {
            val renderCount = AtomicInteger()
            val source = createIcon()
            val smallSize = if (source is VectorDrawable) source.intrinsicWidth else 32
            val largeSize = smallSize * 4
            val imageLoader = ImageLoader.Builder(context)
                .memoryCache { MemoryCache.Builder().maxSizeBytes(4_194_304).build() }
                .components {
                    add(object : Keyer<TestIcon> {
                        override fun key(data: TestIcon, options: Options) = "test-app-icon"
                    })
                    add(object : Fetcher.Factory<TestIcon> {
                        override fun create(data: TestIcon, options: Options, imageLoader: ImageLoader) =
                            object : Fetcher {
                                override suspend fun fetch() = createIcon().toAppIconImage(options).also {
                                    renderCount.incrementAndGet()
                                }
                            }
                    })
                }
                .build()
            try {
                // Rasterizing a vector at its nominal intrinsic size must not
                // mark it as a full-resolution source.
                suspend fun load(size: Int) = imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(TestIcon)
                        .size(size)
                        .precision(Precision.INEXACT)
                        .build(),
                ) as SuccessResult

                val small = load(smallSize)
                val large = load(largeSize)
                val repeated = load(largeSize)
                val smaller = load(smallSize)

                assertEquals(smallSize, small.image.width)
                assertEquals(largeSize, large.image.width)
                assertEquals(DataSource.DISK, large.dataSource)
                assertEquals(DataSource.MEMORY_CACHE, repeated.dataSource)
                assertEquals(DataSource.MEMORY_CACHE, smaller.dataSource)
                assertSame(large.image, repeated.image)
                assertEquals(2, renderCount.get())
            } finally {
                imageLoader.shutdown()
            }
        }
    }

    private fun options(
        size: Size,
        scale: Scale = Scale.FIT,
        precision: Precision = Precision.INEXACT,
        maxSize: Size = Size(4096, 4096),
        bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ) = Options(
        context = context,
        size = size,
        scale = scale,
        precision = precision,
        extras = ImageRequest.Builder(context)
            .maxBitmapSize(maxSize)
            .bitmapConfig(bitmapConfig)
            .build()
            .extras,
    )

    private fun bitmapIcon(width: Int, height: Int) = BitmapDrawable(
        context.resources,
        createBitmap(width, height).apply { eraseColor(Color.GREEN) },
    )

    private fun adaptiveIcon() = AdaptiveIconDrawable(
        ColorDrawable(Color.RED),
        ColorDrawable(Color.TRANSPARENT),
    )

    private fun vectorIcon(): Drawable {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        return requireNotNull(testContext.getDrawable(R.drawable.test_app_icon_vector))
    }

    private object TestIcon
}
