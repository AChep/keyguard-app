package com.artemchep.keyguard.apple.account

import com.artemchep.keyguard.feature.auth.AccountViewState
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.VaultActionSnapshot
import com.artemchep.keyguard.apple.model.VaultItemSnapshot

data class AccountListItemSnapshot(
    val id: String,
    val accountId: String,
    val name: String,
    val title: String,
    val host: String?,
    val error: Boolean,
    val hidden: Boolean,
    val premium: Boolean,
    val syncing: Boolean,
    /** `true` while a multi-selection is active (the row shows a checkmark). */
    val selecting: Boolean,
    /** `true` when this account is part of the active multi-selection. */
    val selected: Boolean,
    /**
     * Handler id that toggles this account's selection membership, routed back via
     * [KeyguardCore.invokeAccountListAction]. Always present, but kept nullable to mirror the folders projection.
     */
    val toggleActionId: String?,
)

data class AccountListSnapshot(
    val loaded: Boolean,
    val items: List<AccountListItemSnapshot>,
    /** Number of selected accounts in the active multi-selection; `0` when none. */
    val selectionCount: Int,
    /** The shared producer's bulk actions of the active multi-selection; empty unless [selectionCount] > 0. */
    val selectionActions: List<VaultActionSnapshot>,
    /**
     * Handler id for the selection's "Sync" bulk action (mirrors the dedicated Sync
     * button on the Compose `AccountsSelection` bar); null when not selecting.
     */
    val selectionSyncActionId: String?,
    /**
     * Handler id for "Select all" (selects every account); null when not selecting,
     * or when all accounts are already selected (the producer's `onSelectAll` is null).
     */
    val selectionSelectAllActionId: String?,
    /** Handler id that clears the active multi-selection; null when not selecting. */
    val selectionClearActionId: String?,
) {
    companion object {
        val empty = AccountListSnapshot(
            loaded = false,
            items = emptyList(),
            selectionCount = 0,
            selectionActions = emptyList(),
            selectionSyncActionId = null,
            selectionSelectAllActionId = null,
            selectionClearActionId = null,
        )
    }
}
/**
 * The shared pending-permissions state is dropped: no native surface renders it yet, and notification
 * authorization is requested on the first posted alert.
 */
data class SyncStatusSnapshot(
    val loaded: Boolean,
    val errorCount: Int,
    val pendingCount: Int,
    val lastSyncTimestampMs: Long?,
) {
    companion object {
        val empty = SyncStatusSnapshot(
            loaded = false,
            errorCount = 0,
            pendingCount = 0,
            lastSyncTimestampMs = null,
        )
    }
}

/** Projects the shared [AccountViewState]; [actions] are its header context actions. */
data class AccountDetailSnapshot(
    val loaded: Boolean,
    val notFound: Boolean,
    val title: String,
    val host: String?,
    val email: String?,
    /** The shared `AccountType` name ("BITWARDEN" / "KEEPASS"), or null while loading. */
    val accountType: String?,
    /** Action id of "open web vault" (Bitwarden), or null when unavailable. */
    val openWebVaultActionId: String?,
    /** Action id of "reveal the local database file" (KeePass), or null when unavailable. */
    val openLocalVaultActionId: String?,
    val items: List<VaultItemSnapshot>,
    val actions: List<VaultActionSnapshot>,
) {
    companion object {
        val empty = AccountDetailSnapshot(
            loaded = false,
            notFound = false,
            title = "",
            host = null,
            email = null,
            accountType = null,
            openWebVaultActionId = null,
            openLocalVaultActionId = null,
            items = emptyList(),
            actions = emptyList(),
        )
    }
}
