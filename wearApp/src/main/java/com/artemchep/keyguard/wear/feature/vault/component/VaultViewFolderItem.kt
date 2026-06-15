package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import com.artemchep.keyguard.feature.home.vault.component.VaultViewFolderTree
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.wear.ui.ProxyMaterial3Styles
import com.artemchep.keyguard.wear.ui.WearListAction

@Composable
fun WearVaultViewFolderItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Folder,
    transformation: SurfaceTransformation? = null,
) {
    WearListAction(
        modifier = modifier
            .fillMaxWidth(),
        title = {
            ProxyMaterial3Styles {
                VaultViewFolderTree(
                    item = item,
                )
            }
        },
        icon = {
            Icon(
                Icons.Outlined.Folder,
                null,
            )
        },
        onClick = item.onClick,
        transformation = transformation,
    )
}
