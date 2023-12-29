package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.vault.model.VaultPasswordHistoryItem

@Composable
fun VaultPasswordHistoryItem(
    item: VaultPasswordHistoryItem,
) = when (item) {
    is VaultPasswordHistoryItem.Value -> VaultPasswordHistoryValueItem(item)
}
