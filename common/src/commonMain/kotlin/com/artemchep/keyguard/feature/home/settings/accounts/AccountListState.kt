package com.artemchep.keyguard.feature.home.settings.accounts

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountItem
import com.artemchep.keyguard.ui.ContextItem

@Immutable
@optics
data class AccountListState(
    val selection: Selection? = null,
    /**
     * Current revision of the items; each revision you should scroll to
     * the top of the list.
     */
    val itemsRevision: Int = 0,
    val items: List<AccountItem> = emptyList(),
    val isLoading: Boolean = true,
    val onAddNewAccount: (() -> Unit)? = null,
) {
    companion object;

    data class Selection(
        val count: Int,
        val actions: List<ContextItem>,
        val onSelectAll: (() -> Unit)? = null,
        val onSync: (() -> Unit)? = null,
        val onClear: (() -> Unit)? = null,
    )
}
