package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.SurfaceTransformation
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem

@Composable
fun WearVaultViewSpacerItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Spacer,
    transformation: SurfaceTransformation? = null,
) {
    Spacer(
        modifier = modifier
            .height(item.height),
    )
}
