package com.artemchep.keyguard.feature.colorpicker

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.ui.icons.AccentColors
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class ColorPickerState(
    val content: Content,
    val onDeny: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val index: Int?,
        val items: ImmutableList<Item>,
    )

    @Immutable
    data class Item(
        val key: Int,
        val color: AccentColors,
        val onClick: () -> Unit,
    )
}
