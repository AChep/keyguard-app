package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.SurfaceTransformation
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.icons.AttachmentIcon
import com.artemchep.keyguard.wear.ui.WearListAction

@Composable
fun WearVaultViewAttachmentItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Attachment,
    transformation: SurfaceTransformation? = null,
) {
    val attachment = item.item
    WearListAction(
        modifier = modifier
            .fillMaxWidth(),
        title = attachment.name,
        text = attachment.size,
        onClick = attachment.preview?.onClick,
        leading = {
            AttachmentIcon(
                name = attachment.name,
                encrypted = true,
            )
        },
        transformation = transformation,
    )
}
