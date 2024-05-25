package com.artemchep.keyguard.feature.barcodetype

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.DialogRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.icon

data class BarcodeTypeRoute(
    val args: Args,
) : DialogRoute {
    companion object {
        fun showInBarcodeTypeActionOrNull(
            translator: TranslatorScope,
            data: String,
            text: String? = null,
            format: BarcodeImageFormat = BarcodeImageFormat.QR_CODE,
            single: Boolean = false,
            navigate: (NavigationIntent) -> Unit,
        ) = if (data.length > 1024) {
            null
        } else {
            showInBarcodeTypeAction(
                translator = translator,
                data = data,
                text = text,
                format = format,
                single = single,
                navigate = navigate,
            )
        }

        fun showInBarcodeTypeAction(
            translator: TranslatorScope,
            data: String,
            text: String? = null,
            format: BarcodeImageFormat = BarcodeImageFormat.QR_CODE,
            single: Boolean = false,
            navigate: (NavigationIntent) -> Unit,
        ) = FlatItemAction(
            leading = icon(Icons.Outlined.QrCode),
            title = Res.string.barcodetype_action_show_in_barcode_title.wrap(),
            onClick = {
                val route = BarcodeTypeRoute(
                    args = Args(
                        text = text,
                        data = data,
                        format = format,
                        single = single,
                    ),
                )
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
            },
        )
    }

    data class Args(
        val text: String?,
        val data: String,
        val format: BarcodeImageFormat,
        val single: Boolean,
    )

    @Composable
    override fun Content() {
        BarcodeTypeScreen(
            args = args,
        )
    }
}
