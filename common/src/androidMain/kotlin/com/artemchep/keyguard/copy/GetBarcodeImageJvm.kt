package com.artemchep.keyguard.copy

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.common.usecase.GetBarcodeImage
import com.artemchep.keyguard.util.encode
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import kotlin.coroutines.CoroutineContext

/**
 * @author Artem Chepurnyi
 */
class GetBarcodeImageJvm(
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetBarcodeImage {
    constructor(directDI: DirectDI) : this()

    override fun invoke(
        request: BarcodeImageRequest,
    ): IO<ImageBitmap> = ioEffect(dispatcher) {
        val bitMatrix = request.encode()
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = if (bitMatrix.get(x, y)) {
                    -0x1000000
                } else {
                    -0x1
                }
                pixels[y * width + x] = color
            }
        }
        val bitmap = Bitmap
            .createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.asImageBitmap()
    }
}
