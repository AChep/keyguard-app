package com.artemchep.keyguard.feature.send.add

import com.artemchep.keyguard.feature.add.AddStateItem
import com.artemchep.keyguard.feature.add.AddStateOwnership
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class SendAddState(
    val title: String = "",
    val ownership: Ownership,
    val sideEffects: SideEffects = SideEffects(),
    val actions: List<FlatItemAction> = emptyList(),
    val items: List<AddStateItem> = emptyList(),
    val onSave: (() -> Unit)? = null,
) {
    data class SideEffects(
        val filePickerIntentFlow: Flow<FilePickerIntent<*>> = emptyFlow(),
    )

    data class Ownership(
        val data: Data,
        val ui: AddStateOwnership,
    ) {
        data class Data(
            val accountId: String?,
        )
    }
}
