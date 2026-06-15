package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.wear.ui.ProxyMaterial3Styles
import com.artemchep.keyguard.wear.ui.WearListAction

@Composable
fun WearVaultViewActionItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Action,
    transformation: SurfaceTransformation? = null,
) {
    WearListAction(
        modifier = modifier
            .fillMaxWidth(),
        title = {
            Text(
                text = item.title,
            )
        },
        text = item.text
            ?.let { text ->
                // composable
                {
                    Text(
                        text = text,
                    )
                }
            },
        icon = item.leading
            ?.let { leading ->
                // composable
                {
                    ProxyMaterial3Styles {
                        Row {
                            leading()
                        }
                    }
                }
        },
        onClick = item.onClick,
        transformation = transformation,
    )
}
