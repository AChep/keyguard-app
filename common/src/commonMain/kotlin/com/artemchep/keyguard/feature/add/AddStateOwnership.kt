package com.artemchep.keyguard.feature.add

import com.artemchep.keyguard.ui.icons.AccentColors

data class AddStateOwnership(
    val account: Element? = null,
    val organization: Element? = null,
    val collection: Element? = null,
    val folder: Element? = null,
    val onClick: (() -> Unit)? = null,
) {
    data class Element(
        val readOnly: Boolean,
        val items: List<Item> = emptyList(),
    ) {
        data class Item(
            val key: String,
            val title: String,
            val text: String? = null,
            val stub: Boolean = false,
            val accentColors: AccentColors? = null,
        )
    }
}
