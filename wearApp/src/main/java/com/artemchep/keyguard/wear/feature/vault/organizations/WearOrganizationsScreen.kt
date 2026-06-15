package com.artemchep.keyguard.wear.feature.vault.organizations

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.map
import com.artemchep.keyguard.feature.home.vault.organizations.OrganizationsRoute
import com.artemchep.keyguard.feature.home.vault.organizations.OrganizationsState
import com.artemchep.keyguard.feature.home.vault.organizations.organizationsScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.items_n
import com.artemchep.keyguard.res.organizations
import com.artemchep.keyguard.res.organizations_empty_label
import com.artemchep.keyguard.ui.icons.KeyguardOrganization
import com.artemchep.keyguard.wear.feature.vault.WearCategoryListScreen
import com.artemchep.keyguard.wear.feature.vault.WearVaultRouteListItem
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearOrganizationsScreen(
    args: OrganizationsRoute.Args,
) {
    val state = organizationsScreenState(
        args = args,
    )
    WearCategoryListScreen(
        icon = Icons.Outlined.KeyguardOrganization,
        title = stringResource(Res.string.organizations),
        emptyLabel = stringResource(Res.string.organizations_empty_label),
        content = state.content.map { it.items },
        itemKey = { it.key },
    ) { item, modifier, transformation ->
        WearOrganizationsListItem(
            modifier = modifier,
            item = item,
            transformation = transformation,
        )
    }
}

@Composable
private fun WearOrganizationsListItem(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    item: OrganizationsState.Content.Item,
    transformation: androidx.wear.compose.material3.SurfaceTransformation? = null,
) {
    val text = run {
        val label = stringResource(Res.string.items_n, item.ciphers)
        label
    }
    WearVaultRouteListItem(
        modifier = modifier,
        title = item.title,
        text = text,
        onClick = item.onViewItemsClick,
        transformation = transformation,
    )
}
