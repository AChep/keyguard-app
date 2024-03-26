package com.artemchep.keyguard.feature.send.add

import com.artemchep.keyguard.feature.add.AddStateItem
import com.artemchep.keyguard.feature.add.AddStateOwnership
import com.artemchep.keyguard.ui.FlatItemAction

data class SendAddState(
    val title: String = "",
    val ownership: Ownership,
    val actions: List<FlatItemAction> = emptyList(),
    val items: List<AddStateItem> = emptyList(),
    val onSave: (() -> Unit)? = null,
) {
    data class Ownership(
        val data: Data,
        val ui: AddStateOwnership,
    ) {
        data class Data(
            val accountId: String?,
        )
    }
}