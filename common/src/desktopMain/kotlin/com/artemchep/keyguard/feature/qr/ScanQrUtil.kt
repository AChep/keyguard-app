package com.artemchep.keyguard.feature.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.awt.image.BufferedImage

object ScanQrUtil {
    fun qrDecodeFromImage(
        bufferedImage: BufferedImage,
    ): String {
        val bfImgLuminanceSource = BufferedImageLuminanceSource(bufferedImage)
        val binaryBmp = BinaryBitmap(HybridBinarizer(bfImgLuminanceSource))
        val qrReader = QRCodeReader()
        return qrReader.decode(binaryBmp).text
    }
}
