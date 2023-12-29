package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardOrganization

@Composable
fun VaultViewOrganizationItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Organization,
) {
    FlatItem(
        modifier = modifier,
        leading = {
            Icon(
                Icons.Outlined.KeyguardOrganization,
                null,
            )
        },
        trailing = {
            ChevronIcon()
        },
        title = {
            Text(
                text = item.title,
                overflow = TextOverflow.Ellipsis,
                maxLines = 5,
            )
        },
        onClick = item.onClick,
    )
}
