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
import com.artemchep.keyguard.ui.focus.rememberTrailingFocusLink
import com.artemchep.keyguard.ui.icons.ChevronIcon

@Composable
fun VaultViewFolderItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Folder,
) {
    // Right moves from the row along the chips of the parent
    // folders. The last node is the folder itself, not a chip.
    val focusLink = rememberTrailingFocusLink(
        enabled = item.onClick != null,
        linked = item.nodes
            .dropLast(1)
            .map { node -> node.onClick != null },
    )
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
                chipModifiers = focusLink.buttons,
            )
        },
        onClick = item.onClick,
        rowModifier = focusLink.row,
    )
}

@Composable
fun VaultViewFolderTree(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Folder,
    chipModifiers: List<Modifier> = emptyList(),
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
        chipModifiers = chipModifiers,
    )
}
