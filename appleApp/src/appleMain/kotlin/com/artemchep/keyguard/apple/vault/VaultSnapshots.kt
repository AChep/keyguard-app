package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.feature.home.vault.quicksearch.QuickSearchHeadlessEmptyState
import com.artemchep.keyguard.feature.home.vault.quicksearch.QuickSearchHeadlessState
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewState
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.TotpFieldSnapshot
import com.artemchep.keyguard.apple.model.VaultActionSnapshot
import com.artemchep.keyguard.apple.model.VaultItemSnapshot
import com.artemchep.keyguard.ui.tabs.CallsTabs

data class VaultDetailSnapshot(
    val title: String,
    val typeIcon: String,
    val favorite: Boolean,
    val isLoading: Boolean,
    val notFound: Boolean,
    val cipherId: String,
    val items: List<VaultItemSnapshot>,
    /**
     * A directly-loadable website favicon URL, or `null` when there is no website icon
     * (icon disabled, no site URL, or a non-login type); the header then falls back to [typeIcon].
     */
    val iconUrl: String? = null,
    /** Initials to render when [iconUrl] is `null` / fails to load. */
    val iconPlaceholder: String? = null,
    /**
     * `null` when the cipher is read-only. Routes back via [KeyguardCore.invokeVaultAction]
     * (or the entry action for a stacked detail).
     */
    val editActionId: String? = null,
    /**
     * The overflow actions of [VaultViewState.Content.Cipher.actions], routed back by
     * [VaultActionSnapshot.id] like [editActionId].
     */
    val actions: List<VaultActionSnapshot> = emptyList(),
) {
    companion object {
        val empty = VaultDetailSnapshot(
            title = "",
            typeIcon = "None",
            favorite = false,
            isLoading = false,
            notFound = false,
            cipherId = "",
            items = emptyList(),
        )
    }
}

/**
 * Keyed by [VaultItemSnapshot.id]. [cipherId] lets SwiftUI ignore badges of a cipher
 * it no longer shows (TOTP row ids are the same for every cipher).
 */
data class VaultDetailTotpSnapshot(
    val cipherId: String,
    val states: Map<String, TotpFieldSnapshot>,
)

/** [title] is localized in shared Kotlin; [key] is the [CallsTabs.key] to pass back to [KeyguardCore.setRecentsTab]. */
data class RecentsTabSnapshot(
    val key: String,
    val title: String,
)
/** [loaded] is `false` while the vault is locked. */
data class RecentsTabsSnapshot(
    val loaded: Boolean,
    val tabs: List<RecentsTabSnapshot>,
    val selectedTabKey: String,
) {
    companion object {
        val empty = RecentsTabsSnapshot(
            loaded = false,
            tabs = emptyList(),
            selectedTabKey = "",
        )
    }
}

enum class QuickSearchEmptyKind {
    LOADING,
    IDLE,
    NO_ITEMS,
    ADD_ACCOUNT,
}

/** [type] routes back via [KeyguardCore.invokeQuickSearchAction]; [shortcut] is the macOS key hint. */
data class QuickSearchActionSnapshot(
    val type: String,
    val title: String,
    val shortcut: String?,
    val selected: Boolean,
)

data class QuickSearchDetailSnapshot(
    val title: String,
    val primaryType: String?,
    val primaryValue: String?,
    val secretType: String?,
    val secretValue: String?,
    val hasOtp: Boolean,
    val launchUrl: String?,
)

/**
 * Only sources the detail pane's favicon (matched by [id] against [QuickSearchSnapshot.selectedItemId]);
 * the result rows render from the `observeQuickSearchListDelta` channel.
 */
data class QuickSearchResultSnapshot(
    val id: String,
    val iconUrl: String?,
    val iconPlaceholder: String?,
)

data class QuickSearchSnapshot(
    val query: String,
    val queryRevision: Int,
    val results: List<QuickSearchResultSnapshot>,
    val emptyState: QuickSearchEmptyKind,
    val selectedItemId: String?,
    val selectedActionIndex: Int?,
    val actions: List<QuickSearchActionSnapshot>,
    val selectedDetail: QuickSearchDetailSnapshot?,
) {
    companion object {
        val empty = QuickSearchSnapshot(
            query = "",
            queryRevision = 0,
            results = emptyList(),
            emptyState = QuickSearchEmptyKind.LOADING,
            selectedItemId = null,
            selectedActionIndex = null,
            actions = emptyList(),
            selectedDetail = null,
        )
    }
}

internal fun QuickSearchHeadlessState.toSnapshot(): QuickSearchSnapshot = QuickSearchSnapshot(
    query = query,
    queryRevision = queryRevision,
    results = results.map { item ->
        QuickSearchResultSnapshot(
            id = item.id,
            iconUrl = item.iconUrl,
            iconPlaceholder = item.iconPlaceholder,
        )
    },
    emptyState = when (emptyState) {
        QuickSearchHeadlessEmptyState.LOADING -> QuickSearchEmptyKind.LOADING
        QuickSearchHeadlessEmptyState.IDLE -> QuickSearchEmptyKind.IDLE
        QuickSearchHeadlessEmptyState.NO_ITEMS -> QuickSearchEmptyKind.NO_ITEMS
        QuickSearchHeadlessEmptyState.ADD_ACCOUNT -> QuickSearchEmptyKind.ADD_ACCOUNT
    },
    selectedItemId = selectedItemId,
    selectedActionIndex = selectedActionIndex,
    actions = actions.map { action ->
        QuickSearchActionSnapshot(
            type = action.type,
            title = action.title,
            shortcut = action.shortcut,
            selected = action.selected,
        )
    },
    selectedDetail = selectedDetail?.let { detail ->
        QuickSearchDetailSnapshot(
            title = detail.title,
            primaryType = detail.primaryType,
            primaryValue = detail.primaryValue,
            secretType = detail.secretType,
            secretValue = detail.secretValue,
            hasOtp = detail.hasOtp,
            launchUrl = detail.launchUrl,
        )
    },
)
