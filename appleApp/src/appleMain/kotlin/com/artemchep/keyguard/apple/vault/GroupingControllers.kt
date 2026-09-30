package com.artemchep.keyguard.apple.vault

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.AddFolderRequest
import com.artemchep.keyguard.common.usecase.RemoveFolderById
import com.artemchep.keyguard.common.usecase.ResolveFolderHierarchyMode
import com.artemchep.keyguard.common.usecase.RenameFolderById
import com.artemchep.keyguard.feature.equivalentdomains.EquivalentDomainsRoute
import com.artemchep.keyguard.feature.equivalentdomains.EquivalentDomainsState
import com.artemchep.keyguard.feature.equivalentdomains.equivalentDomainsScreenStateProducer
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsRoute
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsState
import com.artemchep.keyguard.feature.home.vault.collections.collectionsScreenStateProducer
import com.artemchep.keyguard.feature.home.vault.folders.FoldersRoute
import com.artemchep.keyguard.feature.home.vault.folders.FoldersRouteFactory
import com.artemchep.keyguard.feature.home.vault.folders.FoldersState
import com.artemchep.keyguard.feature.home.vault.folders.foldersScreenStateProducer
import com.artemchep.keyguard.feature.home.vault.organizations.OrganizationsRoute
import com.artemchep.keyguard.feature.home.vault.organizations.OrganizationsState
import com.artemchep.keyguard.feature.home.vault.organizations.organizationsScreenStateProducer
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.model.VaultActionSnapshot
import com.artemchep.keyguard.apple.model.buildSelectionActionSnapshots
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import org.koin.core.scope.Scope

// ---------------------------------------------------------------------------
// Swift-facing snapshots
// ---------------------------------------------------------------------------

/** A flat, Swift-facing projection of the shared `OrganizationsState`. */
data class OrganizationsSnapshot(
    val loaded: Boolean,
    /** The account these organizations belong to (drives the collections push). */
    val accountId: String,
    val items: List<OrganizationListItemSnapshot>,
) {
    companion object {
        val empty = OrganizationsSnapshot(loaded = false, accountId = "", items = emptyList())
    }
}
data class OrganizationListItemSnapshot(
    val id: String,
    val title: String,
    val ciphers: Int,
    /** Handler id for "view items" (a filtered vault list); null when empty. */
    val itemsActionId: String?,
    /** Handler id for the "Info" context action (opens the read-only org info dialog). */
    val infoActionId: String?,
)

/** A flat, Swift-facing projection of the shared `CollectionsState`. */
data class CollectionsSnapshot(
    val loaded: Boolean,
    val items: List<CollectionListItemSnapshot>,
) {
    companion object {
        val empty = CollectionsSnapshot(loaded = false, items = emptyList())
    }
}

data class CollectionListItemSnapshot(
    val id: String,
    val title: String,
    val ciphers: Int,
    /** The owning organization name; used to group rows into sections in Swift. */
    val organizationName: String?,
    val itemsActionId: String?,
    /** Handler id for the "Info" context action (opens the read-only collection info dialog). */
    val infoActionId: String?,
)

/** A flat, Swift-facing projection of the shared `FoldersState`. */
data class FoldersSnapshot(
    val loaded: Boolean,
    /** The account these folders belong to; null disables "add folder". */
    val accountId: String?,
    val items: List<FolderListItemSnapshot>,
    /** Number of selected folders in the active multi-selection; `0` when none. */
    val selectionCount: Int,
    /**
     * The bulk actions of the active multi-selection (view items / rename / merge /
     * delete) — each fires the producer's own confirmation-dialog route through the
     * bridge. Empty unless [selectionCount] > 0.
     */
    val selectionActions: List<VaultActionSnapshot>,
) {
    companion object {
        val empty = FoldersSnapshot(
            loaded = false,
            accountId = null,
            items = emptyList(),
            selectionCount = 0,
            selectionActions = emptyList(),
        )
    }
}

data class FolderListItemSnapshot(
    val renameActionId: String?,
    val deleteActionId: String?,
    val id: String,
    val title: String,
    val ciphers: Int,
    val synced: Boolean,
    val failed: Boolean,
    val itemsActionId: String?,
    /** `true` while a multi-selection is active (the row shows a checkmark). */
    val selecting: Boolean,
    /** `true` when this folder is part of the active multi-selection. */
    val selected: Boolean,
    /**
     * Handler id that toggles this folder's selection membership (a long-press to
     * begin selecting, or a tap while selecting); null when the folder cannot be
     * selected (e.g. a deleted folder). Routes back via `invokeEntryAction`.
     */
    val toggleActionId: String?,
)

/** A flat, Swift-facing projection of the shared `EquivalentDomainsState`. */
data class EquivalentDomainsSnapshot(
    val loaded: Boolean,
    val items: List<EquivalentDomainItemSnapshot>,
) {
    companion object {
        val empty = EquivalentDomainsSnapshot(loaded = false, items = emptyList())
    }
}

data class EquivalentDomainItemSnapshot(
    val isSection: Boolean,
    val id: String,
    /** The joined domain list for a row, or the section header text. */
    val title: String,
    val excluded: Boolean,
    val global: Boolean,
)

/**
 * Recovers the "Info" context action's onClick from a grouping row's [actions]: the
 * single [FlatItemAction] carrying the [Icons.Outlined.Info] icon. Invoking it fires the
 * producer's own `CollectionRoute` / `OrganizationRoute` navigation intent, which the
 * dialog navigation interceptor catches and turns into the native read-only info dialog.
 */
private fun List<ContextItem>.infoOnClick(): (() -> Unit)? =
    filterIsInstance<FlatItemAction>()
        .firstOrNull { it.icon == Icons.Outlined.Info }
        ?.onClick

// ---------------------------------------------------------------------------
// Controllers — each runs the SHARED screen-state producer headlessly (with the
// navigation interceptor) and projects it to a flat snapshot. The per-row
// "view items" closure is captured into the entry's action-handler map so a Swift
// row tap fires the producer's own filtered-vault navigation intent.
// ---------------------------------------------------------------------------

internal class OrganizationsController(
    private val ctx: CoreContext,
) {
    suspend fun produceOrganizationsInto(
        scope: CoroutineScope,
        sessionKoin: Scope,
        accountId: String,
        interceptor: (NavigationIntent) -> Boolean,
        publish: suspend (OrganizationsSnapshot, Map<String, () -> Unit>) -> Unit,
    ) {
        val producerFlow = with(sessionKoin) {
            ctx.koin.newHeadlessStateFlowScope("organizations", scope, interceptor)
                .organizationsScreenStateProducer(
                    args = OrganizationsRoute.Args(accountId = AccountId(accountId)),
                    getOrganizations = get(),
                    getCollections = get(),
                    getCiphers = get(),
                    getCanWrite = get(),
                    vaultRouteFactory = get(),
                    collectionsRouteFactory = get(),
                )
        }
        producerFlow
            .map { state ->
                val handlers = LinkedHashMap<String, () -> Unit>()
                val content = state.content.getOrNull()
                val items = content?.items.orEmpty().map { item ->
                    val itemsActionId = item.onViewItemsClick?.let { onClick ->
                        val id = "${item.key}:items"
                        handlers[id] = onClick
                        id
                    }
                    val infoActionId = item.actions.infoOnClick()?.let { onClick ->
                        val id = "${item.key}:info"
                        handlers[id] = onClick
                        id
                    }
                    OrganizationListItemSnapshot(
                        id = item.key,
                        title = item.title,
                        ciphers = item.ciphers,
                        itemsActionId = itemsActionId,
                        infoActionId = infoActionId,
                    )
                }
                val snapshot = OrganizationsSnapshot(
                    loaded = content != null,
                    accountId = accountId,
                    items = items,
                )
                snapshot to handlers
            }
            .collectOnMain { (snapshot, handlers) -> publish(snapshot, handlers) }
    }
}

internal class CollectionsController(
    private val ctx: CoreContext,
) {
    suspend fun produceCollectionsInto(
        scope: CoroutineScope,
        sessionKoin: Scope,
        accountId: String,
        organizationId: String?,
        interceptor: (NavigationIntent) -> Boolean,
        publish: suspend (CollectionsSnapshot, Map<String, () -> Unit>) -> Unit,
    ) {
        val producerFlow = with(sessionKoin) {
            ctx.koin.newHeadlessStateFlowScope("collections", scope, interceptor)
                .collectionsScreenStateProducer(
                    args = CollectionsRoute.Args(
                        accountId = AccountId(accountId),
                        organizationId = organizationId,
                    ),
                    getOrganizations = get(),
                    getCollections = get(),
                    getCiphers = get(),
                    getCanWrite = get(),
                    vaultRouteFactory = get(),
                )
        }
        producerFlow
            .map { state ->
                val handlers = LinkedHashMap<String, () -> Unit>()
                val content = state.content.getOrNull()
                val items = content?.items.orEmpty()
                    .filterIsInstance<CollectionsState.Content.Item.Collection>()
                    .map { item ->
                        val itemsActionId = item.onViewItemsClick?.let { onClick ->
                            val id = "${item.key}:items"
                            handlers[id] = onClick
                            id
                        }
                        val infoActionId = item.actions.infoOnClick()?.let { onClick ->
                            val id = "${item.key}:info"
                            handlers[id] = onClick
                            id
                        }
                        CollectionListItemSnapshot(
                            id = item.key,
                            title = item.title,
                            ciphers = item.ciphers,
                            organizationName = item.organization?.name,
                            itemsActionId = itemsActionId,
                            infoActionId = infoActionId,
                        )
                    }
                val snapshot = CollectionsSnapshot(
                    loaded = content != null,
                    items = items,
                )
                snapshot to handlers
            }
            .collectOnMain { (snapshot, handlers) -> publish(snapshot, handlers) }
    }
}

internal class FoldersController(
    private val ctx: CoreContext,
) {
    suspend fun produceFoldersInto(
        scope: CoroutineScope,
        sessionKoin: Scope,
        // Null for the watchtower "empty folders" maintenance case: there is no
        // account scope, so the producer lists folders across every account and the
        // Swift screen hides "add folder" (FoldersSnapshot.accountId == null).
        accountId: String?,
        // When set, scope the list to empty folders only (the watchtower card).
        empty: Boolean = false,
        interceptor: (NavigationIntent) -> Boolean,
        publish: suspend (FoldersSnapshot, Map<String, () -> Unit>) -> Unit,
    ) {
        val leContext = ctx.koin.get<LeContext>()
        val filter = accountId
            ?.let { DFilter.ById(id = it, what = DFilter.ById.What.ACCOUNT) }
        val producerFlow = with(sessionKoin) {
            ctx.koin.newHeadlessStateFlowScope("folders", scope, interceptor)
                .foldersScreenStateProducer(
                    args = FoldersRoute.Args(filter = filter, empty = empty),
                    filterContext = get(),
                    confirmationRouteFactory = get(),
                    getFolders = get(),
                    getCiphers = get(),
                    getProfiles = get(),
                    getCanWrite = get(),
                    addFolder = get(),
                    resolveFolderHierarchyMode = get(),
                    mergeFolderById = get(),
                    removeFolderById = get(),
                    renameFolderById = get(),
                    foldersRouteFactory = get<FoldersRouteFactory>(),
                    vaultRouteFactory = get(),
                )
        }
        producerFlow
            .map { state ->
                // All handlers share the entry's single action-handler map (invoked via
                // KeyguardCore.invokeEntryAction): per-row "view items" + per-row selection
                // toggle (folder:toggle) + the bulk-selection actions (selection:action:*,
                // each navigating the producer's own confirmation-dialog route — rename /
                // merge / delete — through the bridge).
                val handlers = LinkedHashMap<String, () -> Unit>()
                val content = state.content.getOrNull()
                val items = content?.items.orEmpty()
                    .filterIsInstance<FoldersState.Content.Item.Folder>()
                    .map { item ->
                        val itemsActionId = item.onViewItemsClick?.let { onClick ->
                            val id = "${item.key}:items"
                            handlers[id] = onClick
                            id
                        }
                        // The per-row tap (while selecting) / long-press (to start a
                        // selection) both toggle this folder's membership; register both
                        // under one id so the Swift checkmark drives the producer selection.
                        val toggleActionId = (item.onLongClick ?: item.onClick)?.let { onClick ->
                            val id = "${item.key}:toggle"
                            handlers[id] = onClick
                            id
                        }
                        fun registerAction(suffix: String): String? {
                            val action = item.actions.filterIsInstance<FlatItemAction>()
                                .firstOrNull { it.id == "folder.${item.key}.$suffix" }
                            val onClick = action?.onClick ?: return null
                            val id = "${item.key}:$suffix"
                            handlers[id] = onClick
                            return id
                        }
                        FolderListItemSnapshot(
                            renameActionId = registerAction("rename"),
                            deleteActionId = registerAction("delete"),
                            id = item.key,
                            title = item.title,
                            ciphers = item.ciphers,
                            synced = item.synced,
                            failed = item.failed,
                            itemsActionId = itemsActionId,
                            selecting = item.selecting,
                            selected = item.selected,
                            toggleActionId = toggleActionId,
                        )
                    }
                val selection = state.selection
                val selectionActions =
                    buildSelectionActionSnapshots(selection?.actions, leContext, handlers)
                // The "clear selection" affordance (the bulk bar's x button).
                selection?.onClear?.let { handlers["selection:clear"] = it }
                val snapshot = FoldersSnapshot(
                    loaded = content != null,
                    accountId = accountId,
                    items = items,
                    selectionCount = selection?.count ?: 0,
                    selectionActions = selectionActions,
                )
                snapshot to handlers
            }
            .collectOnMain { (snapshot, handlers) -> publish(snapshot, handlers) }
    }

    // Native folder mutations — the shared producer drives these through a
    // confirmation-dialog route that the bridge does not host, so the Swift screen
    // calls these thin wrappers over the same use cases directly (the WordlistsView
    // pattern). The list itself still comes from the shared producer above.

    suspend fun addFolder(accountId: String, name: String) {
        val main = ctx.awaitMain()
        val addFolder = main.sessionKoin.get<AddFolder>()
        val resolveFolderHierarchyMode = main.sessionKoin.get<ResolveFolderHierarchyMode>()
        // A new root folder must be created in the representation the account's
        // provider supports (path-based vs parent-id based); the mode is resolved
        // from the account instead of guessed.
        val hierarchyMode = resolveFolderHierarchyMode(AccountId(accountId)).bind()
        addFolder(
            listOf(
                AddFolderRequest(
                    accountId = AccountId(accountId),
                    name = name,
                    hierarchyMode = hierarchyMode,
                ),
            ),
        ).bind()
    }

    suspend fun renameFolder(id: String, name: String) {
        val main = ctx.awaitMain()
        val renameFolderById = main.sessionKoin.get<RenameFolderById>()
        renameFolderById(mapOf(id to name)).bind()
    }

    suspend fun deleteFolder(id: String, trashCiphers: Boolean) {
        val main = ctx.awaitMain()
        val removeFolderById = main.sessionKoin.get<RemoveFolderById>()
        val onConflict = if (trashCiphers) {
            RemoveFolderById.OnCiphersConflict.TRASH
        } else {
            RemoveFolderById.OnCiphersConflict.IGNORE
        }
        removeFolderById(setOf(id), onConflict).bind()
    }
}

internal class EquivalentDomainsController(
    private val ctx: CoreContext,
) {
    suspend fun produceEquivalentDomainsInto(
        scope: CoroutineScope,
        sessionKoin: Scope,
        accountId: String,
        publish: suspend (EquivalentDomainsSnapshot) -> Unit,
    ) {
        // Read-only list — no interceptor needed (the rows have no navigation).
        val producerFlow = with(sessionKoin) {
            ctx.koin.newHeadlessStateFlowScope("equivalent_domains", scope)
                .equivalentDomainsScreenStateProducer(
                    args = EquivalentDomainsRoute.Args(accountId = AccountId(accountId)),
                    getEquivalentDomains = get(),
                )
        }
        producerFlow
            .map { state ->
                val content = state.content.getOrNull()
                val items = content?.items.orEmpty().map { item ->
                    when (item) {
                        is EquivalentDomainsState.Content.Item.Section ->
                            EquivalentDomainItemSnapshot(
                                isSection = true,
                                id = item.key,
                                title = item.text.orEmpty(),
                                excluded = false,
                                global = false,
                            )

                        is EquivalentDomainsState.Content.Item.Content ->
                            EquivalentDomainItemSnapshot(
                                isSection = false,
                                id = item.key,
                                title = item.title,
                                excluded = item.excluded,
                                global = item.global,
                            )
                    }
                }
                EquivalentDomainsSnapshot(loaded = content != null, items = items)
            }
            .collectOnMain { snapshot -> publish(snapshot) }
    }
}
