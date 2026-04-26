package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.icons.KeyguardCollection
import com.artemchep.keyguard.wear.ui.WearListAction

@Composable
fun WearVaultViewCollectionItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Collection,
    transformation: SurfaceTransformation? = null,
) {
    WearListAction(
        modifier = modifier
            .fillMaxWidth(),
        title = {
            Text(
                text = item.title,
                overflow = TextOverflow.Ellipsis,
                maxLines = 5,
            )
        },
        icon = {
            Icon(
                Icons.Outlined.KeyguardCollection,
                null,
            )
        },
        onClick = item.onClick,
        transformation = transformation,
    )
}
