package com.artemchep.keyguard.feature.barcodetype

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.map

@Composable
fun produceBarcodeTypeScreenState(
    args: BarcodeTypeRoute.Args,
): Loadable<BarcodeTypeState> = produceScreenState(
    key = "barcodetype",
    args = arrayOf(
        args,
    ),
    initial = Loadable.Loading,
) {
    fun onClose() {
        navigatePopSelf()
    }

    val storage = kotlin.run {
        val disk = loadDiskHandle("barcodetype")
        PersistedStorage.InDisk(disk)
    }
    val formatDefault = args.format
    val formatSink = mutablePersistedFlow(
        key = "format",
        storage = if (args.single) {
            // Do not save the choice on disk, if we
            // could not change anything.
            PersistedStorage.InMemory
        } else {
            storage
        },
    ) { formatDefault.name }
    val formatFlow = formatSink
        .map {
            kotlin.runCatching {
                BarcodeImageFormat.valueOf(it)
            }.getOrDefault(formatDefault)
        }

    val formatList = if (args.single) {
        listOf(formatDefault)
    } else {
        listOf(
            BarcodeImageFormat.QR_CODE,
            BarcodeImageFormat.CODE_39,
            BarcodeImageFormat.CODE_93,
            BarcodeImageFormat.CODE_128,
            BarcodeImageFormat.PDF_417,
        )
    }
    val formatActions = formatList
        .map { format ->
            FlatItemAction(
                title = TextHolder.Value(format.formatTitle()),
                onClick = {
                    formatSink.value = format.name
                },
            )
        }
    formatFlow
        .map { format ->
            val request = BarcodeImageRequest(
                format = format,
                data = args.data,
            )
            val state = BarcodeTypeState(
                request = request,
                format = BarcodeTypeState.Format(
                    format = format.formatTitle(),
                    options = formatActions,
                ),
                onClose = ::onClose,
            )
            Loadable.Ok(state)
        }
}

private fun BarcodeImageFormat.formatTitle() = when (this) {
    BarcodeImageFormat.AZTEC -> "Aztec 2D"
    BarcodeImageFormat.CODABAR -> "CODABAR 1D"
    BarcodeImageFormat.CODE_39 -> "Code 39 1D"
    BarcodeImageFormat.CODE_93 -> "Code 93 1D"
    BarcodeImageFormat.CODE_128 -> "Code 128 1D"
    BarcodeImageFormat.DATA_MATRIX -> "Data Matrix 2D"
    BarcodeImageFormat.EAN_8 -> "EAN-8 1D"
    BarcodeImageFormat.EAN_13 -> "EAN-13 1D"
    BarcodeImageFormat.ITF -> "ITF (Interleaved Two of Five) 1D"
    BarcodeImageFormat.MAXICODE -> "MaxiCode 2D"
    BarcodeImageFormat.PDF_417 -> "PDF417"
    BarcodeImageFormat.QR_CODE -> "QR Code"
    BarcodeImageFormat.RSS_14 -> "RSS 14"
    BarcodeImageFormat.RSS_EXPANDED -> "RSS EXPANDED"
    BarcodeImageFormat.UPC_A -> "UPC-A 1D"
    BarcodeImageFormat.UPC_E -> "UPC-E 1D"
    BarcodeImageFormat.UPC_EAN_EXTENSION -> "UPC/EAN extension format"
}
