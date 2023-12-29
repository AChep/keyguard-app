package com.artemchep.keyguard.copy

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.common.usecase.GetBarcodeImage
import com.artemchep.keyguard.util.encode
import com.google.zxing.client.j2se.MatrixToImageConfig
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Image
import org.kodein.di.DirectDI
import java.io.ByteArrayOutputStream
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
        val bitmap = bitMatrix.streamAsPng()
            .toByteArray()
            .let(Image::makeFromEncoded)
            .toComposeImageBitmap()
        bitmap
    }

    /**
     * returns a [ByteArrayOutputStream] representation of the QR code
     * @return qrcode as stream
     */
    private fun BitMatrix.streamAsPng(): ByteArrayOutputStream {
        val stream = ByteArrayOutputStream()
        MatrixToImageWriter.writeToStream(
            this,
            "png",
            stream,
            MatrixToImageConfig(),
        )
        return stream
    }
}
