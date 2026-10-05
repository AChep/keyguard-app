package com.artemchep.keyguard.feature.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import coil3.SingletonImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import com.artemchep.keyguard.platform.LeContext
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_QR_IMPORT_IMAGE_SIDE_LENGTH = 2048

actual suspend fun scanBarcodeFromUri(
    context: LeContext,
    uri: String,
) = withContext(Dispatchers.Default) {
    val bitmap = loadQrImportBitmap(
        context = context.context,
        uri = uri.toUri(),
    )
    val source = try {
        createLuminanceSource(bitmap)
    } finally {
        // The memory cache is disabled, so nothing else holds the bitmap.
        bitmap.recycle()
    }
    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
    scanBarcodeFromXzingBitmap(binaryBitmap)
}

/**
 * Decodes a bounded, software-backed bitmap without caching the imported image.
 */
suspend fun loadQrImportBitmap(
    context: Context,
    uri: Uri,
): Bitmap {
    val request = ImageRequest.Builder(context)
        .data(uri)
        .size(MAX_QR_IMPORT_IMAGE_SIDE_LENGTH)
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        // Coil's ImageDecoder path computes a zero target size for extreme
        // aspect ratios and fails, BitmapFactory sampling never goes below 1px.
        .decoderFactory(BitmapFactoryDecoder.Factory())
        // ZXing reads the pixels on the CPU, which hardware bitmaps do not support.
        .allowHardware(false)
        // Imports can contain OTP secrets and should not enter the image caches.
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        .networkCachePolicy(CachePolicy.DISABLED)
        .build()
    return when (val result = SingletonImageLoader.get(context).execute(request)) {
        is SuccessResult -> result.image.toBitmap()
        is ErrorResult -> throw result.throwable
    }
}

private fun createLuminanceSource(bitmap: Bitmap): LuminanceSource {
    // Extract the pixel data from the Bitmap
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    // RGBLuminanceSource ignores alpha, so a transparent
    // background would otherwise read as black.
    if (bitmap.hasAlpha()) {
        for (i in pixels.indices) {
            pixels[i] = ColorUtils.compositeColors(pixels[i], Color.WHITE)
        }
    }
    return RGBLuminanceSource(width, height, pixels)
}
