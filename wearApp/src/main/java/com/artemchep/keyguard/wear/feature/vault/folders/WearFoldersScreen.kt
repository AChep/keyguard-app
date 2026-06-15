package com.artemchep.keyguard.wear.feature.vault.folders

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.map
import com.artemchep.keyguard.feature.home.vault.folders.FoldersRoute
import com.artemchep.keyguard.feature.home.vault.folders.FoldersState
import com.artemchep.keyguard.feature.home.vault.folders.foldersScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.folders
import com.artemchep.keyguard.res.folders_empty_label
import com.artemchep.keyguard.res.items_n
import com.artemchep.keyguard.wear.feature.vault.WearCategoryListScreen
import com.artemchep.keyguard.wear.feature.vault.WearVaultRouteListItem
import com.artemchep.keyguard.wear.feature.vault.WearVaultRouteSectionItem
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearFoldersScreen(
    args: FoldersRoute.Args,
) {
    val state = foldersScreenState(
        args = args,
    )
    WearCategoryListScreen(
        icon = Icons.Outlined.Folder,
        title = stringResource(Res.string.folders),
        emptyLabel = stringResource(Res.string.folders_empty_label),
        content = state.content.map { it.items },
        itemKey = { it.key },
    ) { item, modifier, transformation ->
        WearFoldersListItem(
            modifier = modifier,
            item = item,
            transformation = transformation,
        )
    }
}

@Composable
private fun WearFoldersListItem(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    item: FoldersState.Content.Item,
    transformation: androidx.wear.compose.material3.SurfaceTransformation? = null,
) = when (item) {
    is FoldersState.Content.Item.Section -> WearVaultRouteSectionItem(
        modifier = modifier,
        text = item.text,
        transformation = transformation,
    )

    is FoldersState.Content.Item.Folder -> {
        val text = run {
            val label = stringResource(Res.string.items_n, item.ciphers)

            // If there's an organization name then we want to show
            // both labels, otherwise only show the main one.
            val text = item.text
                ?.takeIf { it.isNotBlank() }
            if (text == null) {
                return@run label
            }
            "$label • $text"
        }
        WearVaultRouteListItem(
            modifier = modifier,
            title = item.title,
            text = text,
            onClick = item.onViewItemsClick,
            transformation = transformation,
        )
    }
}
