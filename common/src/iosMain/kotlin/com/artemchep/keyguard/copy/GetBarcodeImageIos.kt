package com.artemchep.keyguard.copy

import androidx.compose.ui.graphics.ImageBitmap
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.common.usecase.GetBarcodeImage

object GetBarcodeImageIos : GetBarcodeImage {
    override fun invoke(request: BarcodeImageRequest): IO<ImageBitmap> = ioEffect {
        val width = request.size?.width ?: 1
        val height = request.size?.height ?: 1
        ImageBitmap(width, height)
    }
}
