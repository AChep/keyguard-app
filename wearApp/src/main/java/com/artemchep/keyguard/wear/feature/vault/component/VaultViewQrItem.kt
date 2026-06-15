package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.feature.barcodetype.BarcodeImage
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.qr_wifi_description_text
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

private const val MinBarcodeSizePx = 129
private val WearVaultViewQrCodeSize = 180.dp

@Composable
fun WearVaultViewQrItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Qr,
    transformation: SurfaceTransformation? = null,
) {
    val density = LocalDensity.current
    val imageSizePx = with(density) {
        WearVaultViewQrCodeSize.roundToPx().coerceAtLeast(MinBarcodeSizePx)
    }
    val imageRequest = remember(item.data, imageSizePx) {
        BarcodeImageRequest(
            format = BarcodeImageFormat.QR_CODE,
            size = BarcodeImageRequest.Size(
                width = imageSizePx,
                height = imageSizePx,
            ),
            data = item.data,
        )
    }

    Card(
        modifier = modifier
            .heightIn(min = 16.dp),
        colors = CardDefaults.cardColors().run {
            copy(
                containerColor = Color.Transparent,
            )
        },
        transformation = transformation,
    ) {
        Text(
            text = stringResource(Res.string.qr_wifi_description_text),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(12.dp),
        )
        BarcodeImage(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = WearVaultViewQrCodeSize)
                .size(WearVaultViewQrCodeSize)
                .clip(MaterialTheme.shapes.large),
            imageModel = {
                imageRequest
            },
        )
    }
}
