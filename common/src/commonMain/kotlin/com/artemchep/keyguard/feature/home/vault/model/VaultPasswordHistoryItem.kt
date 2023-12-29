package com.artemchep.keyguard.feature.home.vault.model

import arrow.optics.optics
import com.artemchep.keyguard.ui.ContextItem

@optics
sealed interface VaultPasswordHistoryItem {
    companion object

    val id: String

    data class Value(
        override val id: String,
        val title: String,
        val value: String,
        val monospace: Boolean,
        val selected: Boolean,
        val selecting: Boolean,
        /**
         * List of the callable actions appended
         * to the item.
         */
        val dropdown: List<ContextItem> = emptyList(),
        val onClick: (() -> Unit)? = null,
        val onLongClick: (() -> Unit)? = null,
    ) : VaultPasswordHistoryItem {
        companion object
    }
}
