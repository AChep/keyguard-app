package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.wear.ui.WearListCard

@Composable
fun WearVaultViewIdentityItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Identity,
    transformation: SurfaceTransformation? = null,
) {
    val name = listOfNotNull(
        item.data.firstName,
        item.data.middleName,
        item.data.lastName,
    ).joinToString(" ")
    WearListCard(
        modifier = modifier
            .fillMaxWidth(),
        title = item.data.title
            ?.takeIf { it.isNotBlank() }
            ?.let { title ->
                {
                    Text(
                        text = title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        text = {
            Text(
                text = name,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        },
        transformation = transformation,
    )
}
