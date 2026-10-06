package com.artemchep.keyguard.test.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import com.artemchep.keyguard.feature.qr.loadQrImportBitmap
import com.artemchep.keyguard.feature.qr.scanBarcodeFromUri
import com.artemchep.keyguard.platform.LeContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.NotFoundException
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@MediumTest
class QrImageImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val directory = TemporaryFolder(context.filesDir)

    @Test
    fun largePngAndJpegImportsAreBoundedAndReadable() = runBlocking {
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG)) {
            val file = writeQr("large.$format", 4096, 3072, format = format)
            assertImport(file, 2048, 1536)
        }
    }

    @Test
    fun smallAndExtremeAspectRatioImportsPreserveTheCode() = runBlocking {
        assertImport(writeQr("small.png", 256, 256), 256, 256)
        assertImport(writeQr("wide.png", 4096, 512), 2048, 256)
        assertImport(writeQr("tall.png", 512, 4096), 256, 2048)
    }

    @Test
    fun transparentBackgroundReadsAsWhite() = runBlocking {
        val file = writeQr("transparent.png", 512, 512, background = Color.TRANSPARENT)
        assertEquals(PAYLOAD, scan(file))
    }

    @Test
    fun sliverImageReportsAMissingCode() = runBlocking {
        val error = runCatching { scan(writeQr("sliver.png", 1, 5000)) }.exceptionOrNull()
        assertTrue(error is NotFoundException)
    }

    @Test
    fun denseCodeJustAboveTheLimitSurvivesDownsampling() = runBlocking {
        val payload = "qr-import-regression-".repeat(40)
        val file = writeQr("dense.png", 2049, 2049, payload = payload)
        assertImport(file, 2048, 2048, payload)
    }

    @Test
    fun jpegOrientationIsAppliedBeforeScanning() = runBlocking {
        for (orientation in listOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_FLIP_HORIZONTAL)) {
            val file = writeQr(
                "oriented-$orientation.jpg",
                4096,
                3072,
                format = Bitmap.CompressFormat.JPEG,
                markCorner = true,
            )
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            val rotated = orientation == ExifInterface.ORIENTATION_ROTATE_90
            val bitmap = load(file)
            try {
                assertEquals(if (rotated) 1536 else 2048, bitmap.width)
                assertEquals(if (rotated) 2048 else 1536, bitmap.height)
                // Both transformations move the red top-left marker to the top-right.
                val pixel = bitmap.getPixel(bitmap.width - 16, 16)
                assertTrue(Color.red(pixel) > 200)
                assertTrue(Color.green(pixel) < 40)
                assertTrue(Color.blue(pixel) < 40)
            } finally {
                bitmap.recycle()
            }
            assertEquals(PAYLOAD, scan(file))
        }
    }

    @Test
    fun importDoesNotReuseAnOversizedCachedImage() = runBlocking {
        val file = writeQr("cached.png", 3072, 768)
        val imageLoader = SingletonImageLoader.get(context)
        val result = imageLoader.execute(
            ImageRequest.Builder(context)
                .data(uri(file))
                .size(Size.ORIGINAL)
                .allowHardware(false)
                .build(),
        ) as SuccessResult
        val key = requireNotNull(result.memoryCacheKey)
        val cache = requireNotNull(imageLoader.memoryCache)
        try {
            val cached = requireNotNull(cache[key])
            assertEquals(3072, cached.image.width)
            assertImport(file, 2048, 512)
            assertSame(cached.image, cache[key]?.image)
        } finally {
            cache.remove(key)
        }
    }

    @Test
    fun repeatedImportsReadFreshContentsWithoutPopulatingMemoryCache() = runBlocking {
        val file = writeQr("changing.png", 512, 512)
        assertEquals(PAYLOAD, scan(file))
        writeQr("changing.png", 512, 512, payload = "updated-qr-import")
        assertEquals("updated-qr-import", scan(file))

        val cache = requireNotNull(SingletonImageLoader.get(context).memoryCache)
        assertFalse(cache.keys.any { it.key == uri(file).toString() })
    }

    @Test
    fun missingAndInvalidImagesPropagateLoadingErrors() = runBlocking {
        val missing = runCatching { scan(File(directory.root, "missing.png")) }.exceptionOrNull()
        assertTrue(missing is FileNotFoundException)

        val invalid = File(directory.root, "invalid.png").apply { writeText("not an image") }
        val error = runCatching { scan(invalid) }.exceptionOrNull()
        assertTrue(error is IOException || error is IllegalStateException)
    }

    private suspend fun assertImport(
        file: File,
        width: Int,
        height: Int,
        payload: String = PAYLOAD,
    ) {
        val bitmap = load(file)
        try {
            assertEquals(width, bitmap.width)
            assertEquals(height, bitmap.height)
            assertNotEquals(Bitmap.Config.HARDWARE, bitmap.config)
        } finally {
            bitmap.recycle()
        }
        assertEquals(payload, scan(file))
    }

    private suspend fun load(file: File) = loadQrImportBitmap(context, uri(file))

    private suspend fun scan(file: File) = scanBarcodeFromUri(LeContext(context), uri(file).toString())

    private fun uri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileProvider",
        file,
    )

    private fun writeQr(
        name: String,
        width: Int,
        height: Int,
        payload: String = PAYLOAD,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        markCorner: Boolean = false,
        background: Int = Color.WHITE,
    ): File {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0)
        val code = createBitmap(matrix.width, matrix.height)
        val bitmap = createBitmap(width, height)
        try {
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    code.setPixel(x, y, if (matrix[x, y]) Color.BLACK else background)
                }
            }
            bitmap.eraseColor(background)
            val side = minOf(width, height)
            val left = (width - side) / 2
            val top = (height - side) / 2
            Canvas(bitmap).apply {
                drawBitmap(code, null, Rect(left, top, left + side, top + side), null)
                if (markCorner) {
                    drawRect(0f, 0f, 64f, 64f, Paint().apply { color = Color.RED })
                }
            }
            return File(directory.root, name).also { file ->
                file.outputStream().use { output ->
                    check(bitmap.compress(format, 95, output))
                }
            }
        } finally {
            code.recycle()
            bitmap.recycle()
        }
    }

    private companion object {
        const val PAYLOAD = "keyguard-qr-import-regression"
    }
}
