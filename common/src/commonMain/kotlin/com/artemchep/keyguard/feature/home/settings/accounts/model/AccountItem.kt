package com.artemchep.keyguard.feature.home.settings.accounts.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import arrow.optics.optics
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.ui.FlatItemAction
import kotlin.uuid.Uuid

@optics
sealed interface AccountItem {
    companion object

    val id: String

    @optics
    data class Section(
        override val id: String = Uuid.random().toString(),
        val text: String? = null,
    ) : AccountItem {
        companion object
    }

    @optics
    data class Item(
        override val id: String,
        val fav: String? = null, // "https://icons.bitwarden.net/vault.bitwarden.com/icon.png",
        /**
         * The name of the item.
         */
        val name: String,
        val title: AnnotatedString?,
        val text: String?,
        val error: Boolean = false,
        val hidden: Boolean,
        val premium: Boolean,
        val syncing: Boolean = false,
        val selecting: Boolean = false,
        val actionNeeded: Boolean,
        val icon: VaultItemIcon,
        val accentLight: Color,
        val accentDark: Color,
        val isOpened: Boolean,
        val isSelected: Boolean,
        /**
         * List of the callable actions appended
         * to the item.
         */
        val actions: List<FlatItemAction> = emptyList(),
        val onClick: () -> Unit,
        val onLongClick: (() -> Unit)?,
    ) : AccountItem {
        companion object
    }
}
