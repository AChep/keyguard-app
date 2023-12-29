package com.artemchep.keyguard.feature.confirmation.folder

import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2

data class FolderConfirmationState(
    val content: Loadable<Content> = Loadable.Loading,
    val onDeny: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
) {
    data class Content(
        val items: List<Item>,
        val new: TextFieldModel2?,
    ) {
        data class Item(
            val key: String,
            val title: String,
            val selected: Boolean,
            val icon: ImageVector? = null,
            val onClick: (() -> Unit)?,
        )
    }
}
