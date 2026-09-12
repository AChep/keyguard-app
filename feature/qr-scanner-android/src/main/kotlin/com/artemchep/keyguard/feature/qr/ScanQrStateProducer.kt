package com.artemchep.keyguard.feature.qr

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take

@Composable
fun scanQrScreenState(): ScanQrState = produceScreenState(
    key = "scan_qr",
    initial = ScanQrState(),
) {
    val successEventSink = EventFlow<String>()
    val successBarcodeSink = EventFlow<Barcode>()
    successBarcodeSink
        .mapNotNull { it.rawValue }
        .take(1)
        .onEach { rawValue ->
            successEventSink.emit(rawValue)
        }
        .launchIn(this)

    val onScan = { barcodes: List<Barcode> ->
        barcodes.forEach { barcode ->
            successBarcodeSink.emit(barcode)
        }
    }

    val state = ScanQrState(
        effects = ScanQrState.SideEffect(
            onSuccessFlow = successEventSink,
        ),
        onScan = onScan,
    )
    flowOf(state)
}
