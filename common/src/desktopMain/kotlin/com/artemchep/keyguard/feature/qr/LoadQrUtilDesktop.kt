package com.artemchep.keyguard.feature.qr

import com.artemchep.keyguard.platform.LeContext
import com.google.zxing.BinaryBitmap
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.awt.image.BufferedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.imageio.ImageIO

actual suspend fun scanBarcodeFromUri(
    context: LeContext,
    uri: String,
) = withContext(Dispatchers.IO) {
    val url = URL(uri)
    val image = ImageIO.read(url)
    requireNotNull(image) {
        "Unsupported image format"
    }

    withContext(Dispatchers.Default) {
        scanBarcodeFromImage(image)
    }
}

private fun scanBarcodeFromImage(bufferedImage: BufferedImage): String {
    val bfImgLuminanceSource = BufferedImageLuminanceSource(bufferedImage)
    val binaryBmp = BinaryBitmap(HybridBinarizer(bfImgLuminanceSource))
    return scanBarcodeFromXzingBitmap(binaryBmp)
}
