package com.artemchep.keyguard.wear.feature.vault.collections

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.map
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsRoute
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsState
import com.artemchep.keyguard.feature.home.vault.collections.collectionsScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.collections
import com.artemchep.keyguard.res.collections_empty_label
import com.artemchep.keyguard.res.items_n
import com.artemchep.keyguard.ui.icons.KeyguardCollection
import com.artemchep.keyguard.wear.feature.vault.WearCategoryListScreen
import com.artemchep.keyguard.wear.feature.vault.WearVaultRouteListItem
import com.artemchep.keyguard.wear.feature.vault.WearVaultRouteSectionItem
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearCollectionsScreen(
    args: CollectionsRoute.Args,
) {
    val state = collectionsScreenState(
        args = args,
    )
    WearCategoryListScreen(
        icon = Icons.Outlined.KeyguardCollection,
        title = stringResource(Res.string.collections),
        emptyLabel = stringResource(Res.string.collections_empty_label),
        content = state.content.map { it.items },
        itemKey = { it.key },
    ) { item, modifier, transformation ->
        WearCollectionsListItem(
            modifier = modifier,
            item = item,
            transformation = transformation,
        )
    }
}

@Composable
private fun WearCollectionsListItem(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    item: CollectionsState.Content.Item,
    transformation: androidx.wear.compose.material3.SurfaceTransformation? = null,
) = when (item) {
    is CollectionsState.Content.Item.Section -> WearVaultRouteSectionItem(
        modifier = modifier,
        text = item.text,
        transformation = transformation,
    )

    is CollectionsState.Content.Item.Collection -> {
        val text = run {
            val label = stringResource(Res.string.items_n, item.ciphers)

            // If there's an organization name then we want to show
            // both labels, otherwise only show the main one.
            val organizationName = item.organization?.name
                ?.takeIf { it.isNotBlank() }
            if (organizationName == null) {
                return@run label
            }
            "$label • $organizationName"
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
