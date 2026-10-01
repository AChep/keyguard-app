package com.artemchep.keyguard.apple.send

import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.feature.send.view.SendViewState
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.VaultActionSnapshot
import com.artemchep.keyguard.apple.model.VaultFilterItemSnapshot
import com.artemchep.keyguard.apple.model.VaultItemSnapshot
import com.artemchep.keyguard.apple.model.VaultListItemKind
import com.artemchep.keyguard.apple.model.VaultSortItemSnapshot

/**
 * For [VaultListItemKind.ITEM] rows [secretId] + [accountId] identify the Send so the detail pane can observe it
 * via [KeyguardCore.observeSendDetail].
 */
data class SendListItemSnapshot(
    val id: String,
    val kind: VaultListItemKind,
    val secretId: String?,
    val accountId: String?,
    val title: String,
    val text: String?,
)

data class SendListSnapshot(
    val loaded: Boolean,
    val needsAccount: Boolean,
    val query: String,
    val queryRevision: Int,
    /**
     * Changes whenever the query / filter / sort configuration changes. Each revision the list should be read
     * from the top, so the SwiftUI side resets its scroll position on change.
     */
    val itemsRevision: Int,
    val filters: List<VaultFilterItemSnapshot>,
    val sort: List<VaultSortItemSnapshot>,
    val items: List<SendListItemSnapshot>,
    val canClearFilters: Boolean,
    val canClearSort: Boolean,
    val activeFilterCount: Int,
    /** Number of Sends matching the current filter; mirrors the Compose filter pane counter. */
    val totalCount: Int,
    /**
     * Number of Sends in the active multi-selection, or 0 when nothing is selected.
     * The producer owns whether a selection is active; the SwiftUI list owns which
     * rows draw highlighted.
     */
    val selectionCount: Int,
    /** Routed back by [VaultActionSnapshot.id] via [KeyguardCore.invokeSendListSelectionAction]. */
    val selectionActions: List<VaultActionSnapshot>,
    /**
     * The Compose toolbar's options menu, routed back by [VaultActionSnapshot.id] via
     * [KeyguardCore.invokeSendListAction]. The SwiftUI toolbar shows them behind an ellipsis overflow.
     */
    val listActions: List<VaultActionSnapshot> = emptyList(),
    /**
     * Whether dropping a file onto the list creates a File send (the shared `SendListState.onFileDrop` is
     * non-null). The drop goes to [KeyguardCore.dropFileOnSendList].
     */
    val canDropFile: Boolean = false,
    /** Whether an account can create a File send; the native create sheet hides the File type when false. */
    val canCreateFileSend: Boolean = false,
) {
    companion object {
        val empty = SendListSnapshot(
            loaded = false,
            needsAccount = false,
            query = "",
            queryRevision = 0,
            itemsRevision = 0,
            filters = emptyList(),
            sort = emptyList(),
            items = emptyList(),
            canClearFilters = false,
            canClearSort = false,
            activeFilterCount = 0,
            totalCount = 0,
            selectionCount = 0,
            selectionActions = emptyList(),
            listActions = emptyList(),
            canDropFile = false,
            canCreateFileSend = false,
        )
    }
}
/**
 * Projects the shared [SendViewState]. [typeIcon] is the [DSend.Type] name (None / File / Text).
 * [canCopy] / [canShare] / [canEdit] gate the header primary actions [KeyguardCore.sendCopy] /
 * [KeyguardCore.sendShare] / [KeyguardCore.sendEdit]; [actions] are the extra context-menu actions routed by
 * [KeyguardCore.invokeSendAction].
 */
data class SendDetailSnapshot(
    val title: String,
    val typeIcon: String,
    val canCopy: Boolean,
    val canShare: Boolean,
    val canEdit: Boolean,
    val isLoading: Boolean,
    val notFound: Boolean,
    val actions: List<VaultActionSnapshot>,
    val items: List<VaultItemSnapshot>,
) {
    companion object {
        val empty = SendDetailSnapshot(
            title = "",
            typeIcon = "None",
            canCopy = false,
            canShare = false,
            canEdit = false,
            isLoading = false,
            notFound = false,
            actions = emptyList(),
            items = emptyList(),
        )
    }
}
