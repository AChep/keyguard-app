package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.attachments.compose.ItemAttachment
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem

@Composable
fun VaultViewAttachmentItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Attachment,
) {
    ItemAttachment(
        modifier = modifier,
        item = item.item,
    )
}
