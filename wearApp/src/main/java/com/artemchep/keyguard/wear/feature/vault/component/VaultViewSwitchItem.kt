package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.Checkbox
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.wear.ui.WearListCard

@Composable
fun WearVaultViewSwitchItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Switch,
    transformation: SurfaceTransformation? = null,
) {
    WearListCard(
        modifier = modifier
            .fillMaxWidth(),
        title = {
            //
        },
        text = {
            Text(
                text = item.title,
            )
        },
        trailing = {
            Checkbox(
                checked = item.value,
            )
        },
        transformation = transformation,
    )
}
