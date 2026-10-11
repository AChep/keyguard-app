package com.artemchep.keyguard.feature.gpgagent.tools.keypicker

import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.gpgagent.tools.GpgToolsState

data class GpgToolsKeyPickerState(
    val query: TextFieldModel = TextFieldModel.empty,
    val items: List<Item> = emptyList(),
    val noneSelected: Boolean = false,
    val onSelectNone: (() -> Unit)? = null,
    val onDeny: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
) {
    data class Item(
        val key: GpgToolsState.KeyItem,
        val selected: Boolean,
        val onSelect: () -> Unit,
    )
}
