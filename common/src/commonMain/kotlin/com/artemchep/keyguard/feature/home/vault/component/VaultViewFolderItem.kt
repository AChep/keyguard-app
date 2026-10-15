package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.Breadcrumb
import com.artemchep.keyguard.ui.BreadcrumbTree
import com.artemchep.keyguard.ui.icons.ChevronIcon

@Composable
fun VaultViewFolderItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Folder,
) {
    FlatItemSimpleExpressive(
        modifier = modifier,
        shapeState = item.shapeState,
        leading = {
            Icon(
                Icons.Outlined.Folder,
                null,
            )
        },
        trailing = {
            ChevronIcon()
        },
        title = {
            VaultViewFolderTree(
                item = item,
            )
        },
        onClick = item.onClick,
    )
}

@Composable
fun VaultViewFolderTree(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Folder,
) {
    val breadcrumbs = remember(item.nodes) {
        item.nodes.map { node ->
            Breadcrumb(
                name = node.name,
                onClick = node.onClick,
            )
        }
    }
    BreadcrumbTree(
        modifier = modifier,
        breadcrumbs = breadcrumbs,
    )
}
