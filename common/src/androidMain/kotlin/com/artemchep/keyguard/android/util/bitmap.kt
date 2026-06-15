package com.artemchep.keyguard.android.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlin.math.max

/**
 * Extracts a software-backed Bitmap from a Uri.
 */
fun getBitmapFromUri(
    contentResolver: ContentResolver,
    uri: Uri,
    maxSideLength: Int? = null,
): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Force software allocation. Hardware bitmaps will crash
            // when you call bitmap.getPixels()
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            // Downscale the image
            maxSideLength?.let { maxSide ->
                val width = info.size.width
                val height = info.size.height
                val maxDimension = max(width, height)
                if (maxDimension > maxSide) {
                    val scale = maxDimension.toDouble() / maxSide.toDouble()
                    val targetWidth = (width / scale).toInt()
                        .coerceAtLeast(1)
                    val targetHeight = (height / scale).toInt()
                        .coerceAtLeast(1)
                    decoder.setTargetSize(targetWidth, targetHeight)
                }
            }
        }
    } else {
        decodeBitmapLegacy(
            contentResolver = contentResolver,
            uri = uri,
        )
    }
}

private fun decodeBitmapLegacy(
    contentResolver: ContentResolver,
    uri: Uri,
): Bitmap {
    return MediaStore.Images.Media.getBitmap(contentResolver, uri)
}
