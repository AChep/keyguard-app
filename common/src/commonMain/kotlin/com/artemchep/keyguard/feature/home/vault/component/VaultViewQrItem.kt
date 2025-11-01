package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.feature.barcodetype.BarcodeImage
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.qr_wifi_description_text
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.animation.animateContentHeight
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VaultViewQrItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Qr,
) {
    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        VaultViewQrPreviewItem(
            item = item,
        )
        Spacer(
            modifier = Modifier
                .height(24.dp),
        )
    }
}

@Composable
private fun VaultViewQrPreviewItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Qr,
) {
    val imageRequest = remember(item.data) {
        BarcodeImageRequest(
            format = BarcodeImageFormat.QR_CODE,
            data = item.data,
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            modifier = Modifier
                .widthIn(max = 240.dp),
            text = stringResource(Res.string.qr_wifi_description_text),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        BarcodeImage(
            modifier = Modifier
                .size(200.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .animateContentHeight(),
            imageModel = {
                imageRequest
            },
        )
    }
}
