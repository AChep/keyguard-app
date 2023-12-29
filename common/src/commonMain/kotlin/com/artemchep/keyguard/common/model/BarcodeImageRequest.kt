package com.artemchep.keyguard.common.model

data class BarcodeImageRequest(
    val format: BarcodeImageFormat,
    val size: Size? = null,
    val data: String,
) {
    data class Size(
        val width: Int,
        val height: Int,
    )
}
