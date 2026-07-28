package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.vault.component.AccountListItemTextIcon
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cipher_link_unavailable_title
import com.artemchep.keyguard.wear.ui.WearListAction
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearVaultViewLinkItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Link,
    transformation: SurfaceTransformation? = null,
) {
    val presentation = item.presentation
    WearListAction(
        modifier = modifier
            .fillMaxWidth(),
        title = {
            if (presentation != null) {
                WearVaultItemPresentationTitle(item = presentation)
            } else {
                Text(text = stringResource(Res.string.cipher_link_unavailable_title))
            }
        },
        text = presentation?.let { wearVaultItemPresentationText(it, maxLines = 1) },
        icon = {
            if (presentation != null) {
                AccountListItemTextIcon(item = presentation)
            } else {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                )
            }
        },
        onClick = item.onClick,
        transformation = transformation,
    )
}
