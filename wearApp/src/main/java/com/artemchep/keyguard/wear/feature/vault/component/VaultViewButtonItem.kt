package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.SurfaceTransformation
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.wear.ui.WearListAction

@Composable
fun WearVaultViewButtonItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Button,
    transformation: SurfaceTransformation? = null,
) {
    WearListAction(
        modifier = modifier
            .fillMaxWidth(),
        title = item.text,
        onClick = item.onClick,
        leading = item.leading,
        transformation = transformation,
    )
}
