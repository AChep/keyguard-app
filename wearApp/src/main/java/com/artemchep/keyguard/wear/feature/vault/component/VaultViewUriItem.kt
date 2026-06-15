package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ChildButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.wear.ui.ProxyMaterial3Styles

@Composable
fun WearVaultViewUriItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Uri,
    transformation: SurfaceTransformation? = null,
) {
    ChildButton(
        modifier = modifier
            .fillMaxWidth(),
        onClick = {},
        colors = ButtonDefaults.childButtonColors().run {
            copy(
                disabledIconColor = iconColor,
                disabledContentColor = contentColor,
                disabledSecondaryContentColor = secondaryContentColor,
            )
        },
        enabled = false,
        icon = {
            ProxyMaterial3Styles {
                item.icon()
            }
        },
        label = {
            Text(
                text = item.title,
                overflow = TextOverflow.Ellipsis,
                maxLines = 3,
            )
        },
        secondaryLabel = item.text
            ?.let { text ->
                // composable
                {
                    Text(
                        text = text,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 5,
                    )
                }
            },
        transformation = transformation,
    )
}
