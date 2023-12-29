package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem

@Composable
fun VaultViewSpacerItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Spacer,
) {
    Spacer(modifier = modifier.height(item.height))
}
