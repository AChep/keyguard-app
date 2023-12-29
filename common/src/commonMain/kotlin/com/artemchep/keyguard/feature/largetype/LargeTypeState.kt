package com.artemchep.keyguard.feature.largetype

data class LargeTypeState(
    val text: String? = null,
    val index: Int,
    val items: List<Item>,
    val onClose: (() -> Unit)? = null,
) {
    data class Item(
        val text: String,
        val colorize: Boolean,
        val onClick: (() -> Unit)? = null,
    )
}
