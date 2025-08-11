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
import com.artemchep.keyguard.ui.icons.KeyguardCollection

@Composable
fun VaultViewCollectionItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Collection,
) {
    FlatItemSimpleExpressive(
        modifier = modifier,
        shapeState = item.shapeState,
        leading = {
            Icon(
                Icons.Outlined.KeyguardCollection,
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
