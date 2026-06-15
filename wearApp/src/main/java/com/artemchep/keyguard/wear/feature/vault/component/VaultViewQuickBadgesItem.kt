package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.SurfaceTransformation
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem

@Composable
fun WearVaultViewQuickBadgesItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.QuickBadges,
    transformation: SurfaceTransformation? = null,
) {
    // Currently not supported on the Wear OS platform.
}
