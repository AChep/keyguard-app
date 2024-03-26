package com.artemchep.keyguard.feature.confirmation.organization

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2

data class OrganizationConfirmationState(
    val content: Loadable<Content> = Loadable.Loading,
    val onDeny: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
) {
    data class Content(
        val accounts: Section,
        val organizations: Section?,
        val collections: Section?,
        val folders: Section?,
        val folderNew: TextFieldModel2?,
    ) {
        data class Section(
            val items: List<Item>,
            val text: String? = null,
        )

        data class Item(
            val key: String,
            val title: String,
            val text: String? = null,
            val selected: Boolean,
            val icon: (@Composable () -> Unit)? = null,
            val onClick: (() -> Unit)?,
        )
    }
}
