package com.artemchep.keyguard.feature.qr

import com.artemchep.keyguard.platform.LeContext
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader

expect suspend fun scanBarcodeFromUri(
    context: LeContext,
    uri: String,
): String

fun scanBarcodeFromXzingBitmap(bitmap: BinaryBitmap): String {
    val reader = MultiFormatReader()
    return reader.decode(bitmap).text
        .orEmpty()
}
