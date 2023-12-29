package com.artemchep.keyguard.feature.qr

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class ScanQrState(
    val effects: SideEffect = SideEffect(),
    val onScan: ((List<Barcode>) -> Unit)? = null,
) {
    @Immutable
    @optics
    data class SideEffect(
        val onSuccessFlow: Flow<String> = emptyFlow(),
    ) {
        companion object
    }
}
