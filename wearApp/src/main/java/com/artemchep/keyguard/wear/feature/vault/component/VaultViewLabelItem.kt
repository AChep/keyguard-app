package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.SurfaceTransformation
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.wear.ui.WearListLabel

@Composable
fun WearVaultViewLabelItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Label,
    transformation: SurfaceTransformation? = null,
) {
    val textAlign = if (item.horizontalArrangement == Arrangement.Center) {
        TextAlign.Center
    } else {
        TextAlign.Start
    }
    WearListLabel(
        modifier = modifier,
        text = item.text,
        textAlign = textAlign,
        error = item.error,
        transformation = transformation,
    )
}
