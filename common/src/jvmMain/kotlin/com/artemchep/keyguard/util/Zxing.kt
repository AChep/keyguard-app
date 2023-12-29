package com.artemchep.keyguard.util

import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

private const val DEFAULT_SIZE = 1024

fun BarcodeImageRequest.encode(): BitMatrix {
    val bitMatrix = MultiFormatWriter().encode(
        data,
        format.toZxingFormat(),
        size?.width?.takeUnless { it <= 128 } ?: DEFAULT_SIZE,
        size?.height?.takeUnless { it <= 128 } ?: DEFAULT_SIZE,
    )
    return bitMatrix
}

private fun BarcodeImageFormat.toZxingFormat() = when (this) {
    BarcodeImageFormat.AZTEC -> BarcodeFormat.AZTEC
    BarcodeImageFormat.CODABAR -> BarcodeFormat.CODABAR
    BarcodeImageFormat.CODE_39 -> BarcodeFormat.CODE_39
    BarcodeImageFormat.CODE_93 -> BarcodeFormat.CODE_93
    BarcodeImageFormat.CODE_128 -> BarcodeFormat.CODE_128
    BarcodeImageFormat.DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
    BarcodeImageFormat.EAN_8 -> BarcodeFormat.EAN_8
    BarcodeImageFormat.EAN_13 -> BarcodeFormat.EAN_13
    BarcodeImageFormat.ITF -> BarcodeFormat.ITF
    BarcodeImageFormat.MAXICODE -> BarcodeFormat.MAXICODE
    BarcodeImageFormat.PDF_417 -> BarcodeFormat.PDF_417
    BarcodeImageFormat.QR_CODE -> BarcodeFormat.QR_CODE
    BarcodeImageFormat.RSS_14 -> BarcodeFormat.RSS_14
    BarcodeImageFormat.RSS_EXPANDED -> BarcodeFormat.RSS_EXPANDED
    BarcodeImageFormat.UPC_A -> BarcodeFormat.UPC_A
    BarcodeImageFormat.UPC_E -> BarcodeFormat.UPC_E
    BarcodeImageFormat.UPC_EAN_EXTENSION -> BarcodeFormat.UPC_EAN_EXTENSION
}
