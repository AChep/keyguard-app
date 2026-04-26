package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.SurfaceTransformation
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.wear.ui.WearSectionHeader
import com.artemchep.keyguard.wear.ui.WearSectionHeaderEmptyBehavior

@Composable
fun WearVaultViewSectionItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Section,
    transformation: SurfaceTransformation? = null,
) {
    WearSectionHeader(
        title = item.text,
        modifier = modifier,
        emptyBehavior = WearSectionHeaderEmptyBehavior.Spacer4,
        transformation = transformation,
    )
}
