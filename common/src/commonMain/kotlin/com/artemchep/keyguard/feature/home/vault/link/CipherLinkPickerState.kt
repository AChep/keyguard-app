package com.artemchep.keyguard.feature.home.vault.link

import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon

data class CipherLinkPickerState(
    val query: TextFieldModel = TextFieldModel.empty,
    val items: List<Item> = emptyList(),
    val onDeny: (() -> Unit)? = null,
) {
    data class Item(
        val id: String,
        val title: String,
        val text: String,
        val icon: VaultItemIcon,
        val onClick: () -> Unit,
    )
}
