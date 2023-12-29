package com.artemchep.keyguard.feature.barcodetype

import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.ui.FlatItemAction

data class BarcodeTypeState(
    val format: Format,
    val request: BarcodeImageRequest,
    val onClose: (() -> Unit)? = null,
) {
    data class Format(
        val format: String,
        val options: List<FlatItemAction>,
    )
}
