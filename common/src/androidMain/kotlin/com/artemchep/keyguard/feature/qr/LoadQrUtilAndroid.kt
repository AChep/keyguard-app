package com.artemchep.keyguard.feature.qr

import android.graphics.Bitmap
import androidx.core.net.toUri
import com.artemchep.keyguard.android.util.getBitmapFromUri
import com.artemchep.keyguard.platform.LeContext
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_QR_IMPORT_IMAGE_SIDE_LENGTH = 2048

actual suspend fun scanBarcodeFromUri(
    context: LeContext,
    uri: String,
) = withContext(Dispatchers.IO) {
    val bitmap = getBitmapFromUri(
        contentResolver = context.context.contentResolver,
        uri = uri.toUri(),
        maxSideLength = MAX_QR_IMPORT_IMAGE_SIDE_LENGTH,
    )
    withContext(Dispatchers.Default) {
        scanBarcodeFromBitmap(bitmap)
    }
}

private fun scanBarcodeFromBitmap(bitmap: Bitmap): String {
    // Extract the pixel data from the Bitmap
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val source = RGBLuminanceSource(width, height, pixels)
    val binarizer = HybridBinarizer(source)
    val binaryBitmap = BinaryBitmap(binarizer)
    return scanBarcodeFromXzingBitmap(binaryBitmap)
}
