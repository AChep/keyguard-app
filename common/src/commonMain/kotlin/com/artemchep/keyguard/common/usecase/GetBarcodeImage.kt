package com.artemchep.keyguard.common.usecase

import androidx.compose.ui.graphics.ImageBitmap
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.BarcodeImageRequest

interface GetBarcodeImage : (BarcodeImageRequest) -> IO<ImageBitmap>
