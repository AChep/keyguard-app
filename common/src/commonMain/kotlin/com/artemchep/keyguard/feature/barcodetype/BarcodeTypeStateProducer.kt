package com.artemchep.keyguard.feature.barcodetype

import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.GetBarcodeUsageHistory
import com.artemchep.keyguard.common.usecase.PutBarcodeUsageHistory
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instanceOrNull

@Composable
fun produceBarcodeTypeScreenState(
    args: BarcodeTypeRoute.Args,
): Loadable<BarcodeTypeState> = with(localDI().direct) {
    produceBarcodeTypeScreenState(
        args = args,
        getBarcodeUsageHistory = instanceOrNull(),
        putBarcodeUsageHistory = instanceOrNull(),
    )
}

@Composable
fun produceBarcodeTypeScreenState(
    args: BarcodeTypeRoute.Args,
    getBarcodeUsageHistory: GetBarcodeUsageHistory?,
    putBarcodeUsageHistory: PutBarcodeUsageHistory?,
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

    val formatDefault = args.format
    val formatList = if (args.disallowFormatSelection) {
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

    val storage = kotlin.run {
        val disk = loadDiskHandle("barcodetype")
        PersistedStorage.InDisk(disk)
    }

    val (formatFlow, onFormatSet) = if (args.disallowFormatSelection) {
        val formatFlow = flowOf(formatDefault)
        val onFormatSet: (BarcodeImageFormat) -> Unit = {
            // No op
        }

        formatFlow to onFormatSet
    } else {
        val historyKey = args.historyKey
            .takeUnless { args.disallowFormatSelection }
        if (
            historyKey != null &&
            getBarcodeUsageHistory != null &&
            putBarcodeUsageHistory != null
        ) {
            val formatSaved = kotlin.runCatching {
                val entry = getBarcodeUsageHistory(historyKey)
                    .firstOrNull()
                entry?.type
                    ?.toBarcodeImageFormatOrNull()
                    ?.takeIf { it in formatList }
            }.getOrNull()
                ?: formatDefault
            val formatSink = MutableStateFlow(formatSaved)
            val onFormatSet: (BarcodeImageFormat) -> Unit = onFormatSet@{ format ->
                if (formatSink.value == format) {
                    return@onFormatSet
                }

                formatSink.value = format
                putBarcodeUsageHistory(historyKey, format.name)
                    .attempt()
                    .launchIn(appScope)
            }
            formatSink to onFormatSet
        } else {
            val formatSink = mutablePersistedFlow(
                key = "format",
                storage = storage,
            ) { formatDefault.name }
            val onFormatSet: (BarcodeImageFormat) -> Unit = { format ->
                formatSink.value = format.name
            }

            val formatFlow = formatSink
                .map {
                    it.toBarcodeImageFormatOrNull()
                        ?: formatDefault
                }
            formatFlow to onFormatSet
        }
    }
    val formatActions = formatList
        .map { format ->
            FlatItemAction(
                title = TextHolder.Value(format.formatTitle()),
                onClick = onFormatSet
                    .partially1(format),
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

private fun String.toBarcodeImageFormatOrNull(
): BarcodeImageFormat? =
    kotlin.runCatching {
        BarcodeImageFormat.valueOf(this)
    }.getOrNull()

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
