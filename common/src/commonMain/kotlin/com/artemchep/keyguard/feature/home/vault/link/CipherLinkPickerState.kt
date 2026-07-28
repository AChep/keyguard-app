package com.artemchep.keyguard.feature.home.vault.link

import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.home.vault.model.VaultItemPresentation

data class CipherLinkPickerState(
    val query: TextFieldModel = TextFieldModel.empty,
    val items: List<Item> = emptyList(),
    val onDeny: (() -> Unit)? = null,
) {
    data class Item(
        val presentation: VaultItemPresentation,
        val onClick: () -> Unit,
    )
}
