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
import com.artemchep.keyguard.common.usecase.ResolveFolderHierarchyMode
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

// Swift-facing snapshots

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

data class FoldersSnapshot(
    val loaded: Boolean,
    val title: String?,
    /** The account these folders belong to; null disables "add folder". */
    val accountId: String?,
    val parentId: String?,
    val addActionId: String?,
    val itemsCount: Int,
    val itemsActionId: String?,
    val items: List<FolderListItemSnapshot>,
    /** Number of selected folders in the active multi-selection; `0` when none. */
    val selectionCount: Int,
    /** Empty unless [selectionCount] > 0. */
    val selectionActions: List<VaultActionSnapshot>,
) {
    companion object {
        val empty = FoldersSnapshot(
            loaded = false,
            title = null,
            accountId = null,
            parentId = null,
            addActionId = null,
            itemsCount = 0,
            itemsActionId = null,
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
    /** Opens folder contents, including the action to create its first child. */
    val navigationActionId: String?,
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
 * The "Info" context action is the single [FlatItemAction] carrying the [Icons.Outlined.Info] icon.
 * Its `CollectionRoute` / `OrganizationRoute` intent is caught by the dialog navigation interceptor
 * and shown as the native read-only info dialog.
 */
private fun List<ContextItem>.infoOnClick(): (() -> Unit)? =
    filterIsInstance<FlatItemAction>()
        .firstOrNull { it.icon == Icons.Outlined.Info }
        ?.onClick

// Controllers

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

internal fun FoldersState.Content.Item.Folder.toFolderListItemSnapshot(
    handlers: MutableMap<String, () -> Unit>,
): FolderListItemSnapshot {
    fun register(suffix: String, action: (() -> Unit)?): String? = action?.let {
        val id = "$key:$suffix"
        handlers[id] = it
        id
    }
    fun registerAction(suffix: String): String? {
        val action = actions.filterIsInstance<FlatItemAction>()
            .firstOrNull { it.id == "folder.$key.$suffix" }
        return register(suffix, action?.onClick)
    }
    val itemsActionId = register("items", onViewItemsClick)
    return FolderListItemSnapshot(
        renameActionId = registerAction("rename"),
        deleteActionId = registerAction("delete"),
        id = key,
        title = title,
        ciphers = ciphers,
        synced = synced,
        failed = failed,
        itemsActionId = itemsActionId,
        navigationActionId = register("open", onClick),
        selecting = selecting,
        selected = selected,
        toggleActionId = register("toggle", onLongClick ?: onClick),
    )
}

internal class FoldersController(
    private val ctx: CoreContext,
) {
    suspend fun produceFoldersInto(
        scope: CoroutineScope,
        sessionKoin: Scope,
        args: FoldersRoute.Args,
        interceptor: (NavigationIntent) -> Boolean,
        publish: suspend (FoldersSnapshot, Map<String, () -> Unit>) -> Unit,
    ) {
        val leContext = ctx.koin.get<LeContext>()
        val accountId = args.parent?.accountId ?: DFilter.findOne<DFilter.ById>(args.filter ?: DFilter.All) {
            it.what == DFilter.ById.What.ACCOUNT
        }?.id
        val producerFlow = with(sessionKoin) {
            ctx.koin.newHeadlessStateFlowScope("folders", scope, interceptor)
                .foldersScreenStateProducer(
                    args = args,
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
                val handlers = LinkedHashMap<String, () -> Unit>()
                val content = state.content.getOrNull()
                val items = content?.items.orEmpty()
                    .filterIsInstance<FoldersState.Content.Item.Folder>()
                    .map { it.toFolderListItemSnapshot(handlers) }
                val itemsActionId = content?.ciphers?.onClick?.let {
                    handlers["folder:items"] = it
                    "folder:items"
                }
                val selection = state.selection
                val selectionActions =
                    buildSelectionActionSnapshots(selection?.actions, leContext, handlers)
                // The "clear selection" affordance (the bulk bar's x button).
                selection?.onClear?.let { handlers["selection:clear"] = it }
                val snapshot = FoldersSnapshot(
                    loaded = content != null,
                    title = state.title,
                    accountId = accountId,
                    parentId = args.parent?.folderId,
                    addActionId = state.onAdd?.let {
                        handlers["folder:add"] = it
                        "folder:add"
                    },
                    itemsCount = content?.ciphers?.count ?: 0,
                    itemsActionId = itemsActionId,
                    items = items,
                    selectionCount = selection?.count ?: 0,
                    selectionActions = selectionActions,
                )
                snapshot to handlers
            }
            .collectOnMain { (snapshot, handlers) -> publish(snapshot, handlers) }
    }

    // Add calls the use case directly; rename / delete go through the producer's own row actions.

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
