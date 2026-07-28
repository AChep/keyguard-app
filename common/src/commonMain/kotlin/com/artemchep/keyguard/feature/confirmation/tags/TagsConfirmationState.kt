package com.artemchep.keyguard.feature.confirmation.tags

import com.artemchep.keyguard.feature.auth.common.TextFieldModel

data class TagsConfirmationState(
    val items: List<Item> = emptyList(),
    val onAdd: (() -> Unit)? = null,
    val onDeny: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
) {
    data class Item(
        val key: String,
        val field: TextFieldModel,
        val onRemove: (() -> Unit)? = null,
    )
}
