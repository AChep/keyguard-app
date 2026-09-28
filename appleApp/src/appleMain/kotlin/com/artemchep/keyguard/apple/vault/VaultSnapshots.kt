package com.artemchep.keyguard.apple.vault

import arrow.core.right
import com.artemchep.keyguard.main
import com.artemchep.keyguard.feature.home.vault.quicksearch.QuickSearchHeadlessEmptyState
import com.artemchep.keyguard.feature.home.vault.quicksearch.QuickSearchHeadlessState
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewState
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.TotpFieldSnapshot
import com.artemchep.keyguard.apple.model.VaultActionSnapshot
import com.artemchep.keyguard.apple.model.VaultItemSnapshot
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.tabs.CallsTabs
import kotlinx.coroutines.flow.map

/**
 * A flat, Swift-friendly projection of the shared [VaultViewState] for the
 * SwiftUI vault item detail panel. Built by [KeyguardCore.buildDetailSnapshot].
 */
data class VaultDetailSnapshot(
    val title: String,
    val typeIcon: String,
    val favorite: Boolean,
    val isLoading: Boolean,
    val notFound: Boolean,
    val cipherId: String,
    val items: List<VaultItemSnapshot>,
    /**
     * A concrete, directly-loadable website favicon URL for the header, or `null`
     * when there is no website icon (icon disabled, no site URL, or a non-login
     * type). The SwiftUI header falls back to [typeIcon] in that case.
     */
    val iconUrl: String? = null,
    /** Initials to render when [iconUrl] is `null` / fails to load. */
    val iconPlaceholder: String? = null,
    /**
     * The synthesized id of the cipher's edit action, registered in the controller's
     * action-handler map, or `null` when the cipher is read-only. The SwiftUI header
     * renders a dedicated edit button and routes it back via [KeyguardCore.invokeVaultAction]
     * (or the entry action for a stacked detail).
     */
    val editActionId: String? = null,
    /**
     * The cipher's top-level overflow actions (move to folder, change password, create
     * send, export, archive, trash, delete, view password history, …) — the shared
     * `VaultViewState.Content.Cipher.actions` projected to flat snapshots and routed
     * back by [VaultActionSnapshot.id] via [KeyguardCore.invokeVaultAction]. Mirrors the
     * Compose toolbar's options menu.
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
 * The live TOTP badges of one cipher detail, keyed by [VaultItemSnapshot.id].
 * Delivered on its own channel so the per-second countdown does not rebuild the
 * whole [VaultDetailSnapshot]. [cipherId] lets SwiftUI ignore badges of a cipher
 * it no longer shows (TOTP row ids are the same for every cipher).
 */
data class VaultDetailTotpSnapshot(
    val cipherId: String,
    val states: Map<String, TotpFieldSnapshot>,
)

/**
 * One tab of the Recents window — "Recently opened" / "Often opened" — with its
 * title localized in shared Kotlin. [key] is the [CallsTabs.key] to pass back to
 * [KeyguardCore.setRecentsTab].
 */
data class RecentsTabSnapshot(
    val key: String,
    val title: String,
)
/**
 * The Recents window's tab bar as its OWN small channel, split off the
 * item stream: the item rows now ride the state-anchored [VaultListDelta]
 * pipeline ([RecentsController.observeRecentsListDelta], off-main), while this
 * cheap on-main channel carries just the tab titles + current selection so the
 * segmented `Picker` never depends on the item projection. [loaded] is `true`
 * once the shared producer is live (an unlocked vault); `false` while locked.
 */
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

/** A copy / open action for the selected item. [type] routes back via
 * [KeyguardCore.invokeQuickSearchAction]; [shortcut] is the macOS key hint. */
data class QuickSearchActionSnapshot(
    val type: String,
    val title: String,
    val shortcut: String?,
    val selected: Boolean,
)

/** The highlighted item's detail, shown in the quick-search right pane. */
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
 * One quick-search result row, slimmed to only what the SwiftUI overlay still reads:
 * the row [id] (matched against [QuickSearchSnapshot.selectedItemId] to source the
 * detail pane's favicon) plus that favicon's [iconUrl] / [iconPlaceholder]. The
 * result ROWS themselves render from the shared diffable store (the
 * `observeQuickSearchListDelta` channel), so no rich per-row projection is carried
 * on the snapshot anymore.
 */
data class QuickSearchResultSnapshot(
    val id: String,
    val iconUrl: String?,
    val iconPlaceholder: String?,
)

/** Flat projection of the quick-search overlay state. Built by [KeyguardCore.observeQuickSearch]. */
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
