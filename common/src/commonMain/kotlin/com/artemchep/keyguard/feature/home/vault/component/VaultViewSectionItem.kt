package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem

@Composable
fun VaultViewSectionItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Section,
) {
    Section(
        modifier = modifier,
        text = item.text,
    )
}
