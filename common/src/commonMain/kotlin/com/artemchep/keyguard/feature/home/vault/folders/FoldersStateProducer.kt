package com.artemchep.keyguard.feature.home.vault.folders

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.AddFolderRequest
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.MergeFolderById
import com.artemchep.keyguard.common.usecase.RemoveFolderById
import com.artemchep.keyguard.common.usecase.RenameFolderById
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.core.store.bitwarden.exists
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.confirmation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactory
import com.artemchep.keyguard.feature.home.vault.by
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.mapListShape
import com.artemchep.keyguard.provider.bitwarden.usecase.util.canDelete
import com.artemchep.keyguard.provider.bitwarden.usecase.util.canEdit
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardCipher
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.selection.selectionHandle
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun foldersScreenState(
    args: FoldersRoute.Args,
) = with(localDI().direct) {
    foldersScreenState(
        args = args,
        directDI = this,
        getFolders = instance(),
        getCiphers = instance(),
        getCanWrite = instance(),
        addFolder = instance(),
        mergeFolderById = instance(),
        removeFolderById = instance(),
        renameFolderById = instance(),
        foldersRouteFactory = instance(),
        vaultRouteFactory = instance(),
    )
}

@Composable
fun foldersScreenState(
    args: FoldersRoute.Args,
    directDI: DirectDI,
    getFolders: GetFolders,
    getCiphers: GetCiphers,
    getCanWrite: GetCanWrite,
    addFolder: AddFolder,
    mergeFolderById: MergeFolderById,
    removeFolderById: RemoveFolderById,
    renameFolderById: RenameFolderById,
    foldersRouteFactory: FoldersRouteFactory,
    vaultRouteFactory: VaultRouteFactory,
): FoldersState = produceScreenState(
    key = "folders",
    initial = FoldersState(),
    args = arrayOf(
        args,
        getFolders,
        getCiphers,
        getCanWrite,
        addFolder,
        mergeFolderById,
        removeFolderById,
        renameFolderById,
    ),
) {
    foldersScreenStateProducer(
        args = args,
        directDI = directDI,
        getFolders = getFolders,
        getCiphers = getCiphers,
        getCanWrite = getCanWrite,
        addFolder = addFolder,
        mergeFolderById = mergeFolderById,
        removeFolderById = removeFolderById,
        renameFolderById = renameFolderById,
        foldersRouteFactory = foldersRouteFactory,
        vaultRouteFactory = vaultRouteFactory,
    )
}

suspend fun RememberStateFlowScope.foldersScreenStateProducer(
    args: FoldersRoute.Args,
    directDI: DirectDI,
    getFolders: GetFolders,
    getCiphers: GetCiphers,
    getCanWrite: GetCanWrite,
    addFolder: AddFolder,
    mergeFolderById: MergeFolderById,
    removeFolderById: RemoveFolderById,
    renameFolderById: RenameFolderById,
    foldersRouteFactory: FoldersRouteFactory,
    vaultRouteFactory: VaultRouteFactory,
): Flow<FoldersState> {
    val confirmationRouteFactory: ConfirmationRouteFactory = directDI.instance()

    data class NodeWithCiphers(
        val node: FolderBrowseNode,
        val directCiphers: List<DSecret>,
        val subtreeCiphers: List<DSecret>,
    )

    val foldersComparator = Comparator { a: DFolder, b: DFolder ->
        AlphabeticalSort.compareStr(a.name, b.name)
    }
    val foldersFilter = args.filter ?: DFilter.All
    val foldersFlow = getFolders()
        .map { folders ->
            folders
                .sortedWith(foldersComparator)
        }
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)
    val ciphersByFolderFlow = getCiphers()
        .map { ciphers ->
            ciphers
                .filter { it.deletedDate == null }
                .groupBy { it.folderId }
        }
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)
    val visibleFolderIdsFlow = foldersFlow
        .combine(ciphersByFolderFlow) { folders, ciphersByFolder ->
            val predicate = foldersFilter.prepareFolders(directDI, folders)
            folders
                .asSequence()
                .filter { predicate(it) }
                .filter { folder ->
                    !args.empty || ciphersByFolder[folder.id].orEmpty().isEmpty()
                }
                .map { it.id }
                .toSet()
        }
        .distinctUntilChanged()
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)
    val browseTreeFlow = foldersFlow
        .combine(visibleFolderIdsFlow) { folders, visibleFolderIds ->
            buildFolderBrowseTree(
                folders = folders,
                visibleFolderIds = visibleFolderIds,
                parent = args.parent,
            )
        }
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)
    val nodesWithCiphersFlow = browseTreeFlow
        .combine(ciphersByFolderFlow) { tree, ciphersByFolder ->
            val nodes = tree.items
                .map { node ->
                    val directCiphers = node.directFolderIds
                        .asSequence()
                        .flatMap { folderId ->
                            ciphersByFolder[folderId].orEmpty()
                        }
                        .distinctBy { it.id }
                        .toList()
                    val subtreeCiphers = node.descendantFolderIds
                        .asSequence()
                        .flatMap { folderId ->
                            ciphersByFolder[folderId].orEmpty()
                        }
                        .distinctBy { it.id }
                        .toList()
                    NodeWithCiphers(
                        node = node,
                        directCiphers = directCiphers,
                        subtreeCiphers = subtreeCiphers,
                    )
                }
            tree to nodes
        }
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)

    val selectionHandle = selectionHandle("selection")
    val selectedNodesWithCiphersFlow = nodesWithCiphersFlow
        .combine(selectionHandle.idsFlow) { (_, nodesWithCiphers), selectedNodeKeys ->
            selectedNodeKeys
                .mapNotNull { selectedNodeKey ->
                    val nodeWithCiphers = nodesWithCiphers
                        .firstOrNull { it.node.key == selectedNodeKey }
                        ?: return@mapNotNull null
                    selectedNodeKey to nodeWithCiphers
                }
                .toMap()
        }
        .distinctUntilChanged()
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)

    val accountId = DFilter
        .findOne<DFilter.ById>(foldersFilter) { f ->
            f.what == DFilter.ById.What.ACCOUNT
        }
        ?.id

    suspend fun onAdd() {
        fun createRequest(name: String): AddFolderRequest? {
            val requestInfo = args.parent
                ?.createAddFolderRequest(name)
                ?: AddFolderRequestInfo(
                    accountId = accountId
                        ?: return null,
                    name = name,
                    parentId = null,
                    hierarchyMode = FolderHierarchyMode.Path,
                )
            return AddFolderRequest(
                accountId = AccountId(requestInfo.accountId),
                name = requestInfo.name,
                parentId = requestInfo.parentId,
                hierarchyMode = requestInfo.hierarchyMode,
            )
        }

        val intent = createConfirmationDialogIntent(
            confirmationRouteFactory = confirmationRouteFactory,
            item = ConfirmationRoute.Args.Item.StringItem(
                key = "name",
                title = translate(Res.string.generic_name),
                // Folder must have a non-empty name!
                canBeEmpty = false,
            ),
            title = translate(Res.string.folder_action_create_title),
        ) { name ->
            val request = createRequest(name)
                ?: return@createConfirmationDialogIntent
            addFolder(listOf(request))
                .launchIn(appScope)
        }
        navigate(intent)
    }

    suspend fun onRename(
        nodes: List<FolderBrowseNode>,
    ) {
        if (nodes.isEmpty()) {
            return
        }
        val route = confirmationRouteFactory.registerRouteResultReceiver(
            args = ConfirmationRoute.Args(
                icon = icon(Icons.Outlined.Edit),
                title = if (nodes.size > 1) {
                    translate(Res.string.folder_action_change_names_title)
                } else {
                    translate(Res.string.folder_action_change_name_title)
                },
                items = nodes
                    .sortedWith(StringComparatorIgnoreCase { it.name })
                    .map { node ->
                        ConfirmationRoute.Args.Item.StringItem(
                            key = node.key,
                            value = node.name,
                            title = node.name,
                            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
                            canBeEmpty = false,
                        )
                    },
            ),
        ) {
            if (it is ConfirmationResult.Confirm) {
                val folderIdsToNames = kotlin.run {
                    val namesByNodeKey = it.data
                        .mapValues { it.value as String }
                    createFolderRenameMap(
                        nodes = nodes,
                        namesByNodeKey = namesByNodeKey,
                    )
                }
                if (folderIdsToNames.isNotEmpty()) {
                    renameFolderById(folderIdsToNames)
                        .launchIn(appScope)
                }
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    suspend fun onMerge(
        folderName: String,
        folderIds: Set<String>,
    ) {
        val folderNameKey = "folder_name"
        val folderNameItem = ConfirmationRoute.Args.Item.StringItem(
            key = folderNameKey,
            value = folderName,
            title = translate(Res.string.generic_name),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
            canBeEmpty = false,
        )

        val route = confirmationRouteFactory.registerRouteResultReceiver(
            args = ConfirmationRoute.Args(
                icon = icon(Icons.Outlined.Merge),
                title = translate(Res.string.folder_action_merge_confirmation_title),
                items = listOfNotNull(
                    folderNameItem,
                ),
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                selectionHandle.clearSelection()

                val name = result.data[folderNameKey] as String
                mergeFolderById(folderIds, name)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    suspend fun onDelete(
        folderIds: Set<String>,
        /**
         * `true` if any of the folders contain ciphers in it,
         * `false` otherwise.
         */
        hasCiphers: Boolean,
    ) {
        val cascadeRemoveKey = "cascade_remove"
        val cascadeRemoveItem = if (hasCiphers) {
            ConfirmationRoute.Args.Item.BooleanItem(
                key = cascadeRemoveKey,
                title = translate(Res.string.ciphers_action_cascade_trash_associated_items_title),
            )
        } else {
            null
        }

        val route = confirmationRouteFactory.registerRouteResultReceiver(
            args = ConfirmationRoute.Args(
                icon = icon(Icons.Outlined.Delete),
                title = if (folderIds.size > 1) {
                    translate(Res.string.folder_delete_many_confirmation_title)
                } else {
                    translate(Res.string.folder_delete_one_confirmation_title)
                },
                message = translate(Res.string.folder_delete_confirmation_text),
                items = listOfNotNull(
                    cascadeRemoveItem,
                ),
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                selectionHandle.clearSelection()

                val onCiphersConflict = kotlin.run {
                    val shouldRemoveAssociatedCiphers = result.data[cascadeRemoveKey] == true
                    if (shouldRemoveAssociatedCiphers) {
                        RemoveFolderById.OnCiphersConflict.TRASH
                    } else {
                        RemoveFolderById.OnCiphersConflict.IGNORE
                    }
                }
                removeFolderById(folderIds, onCiphersConflict)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    val selectionFlow = combine(
        nodesWithCiphersFlow,
        selectedNodesWithCiphersFlow,
        getCanWrite(),
    ) { (_, nodesWithCiphers), selectedNodes, canWrite ->
        if (selectedNodes.isEmpty()) {
            return@combine null
        }

        val nodeKeys = nodesWithCiphers
            .mapTo(mutableSetOf()) { it.node.key }
        val selectedNodeKeys = selectedNodes.keys
        // Find folders that have some limitations
        val hasCanNotEditFolders = selectedNodes.values
            .flatMap { it.node.descendantFolders }
            .any { !it.service.canEdit() }
        val hasCanNotDeleteFolders = selectedNodes.values
            .flatMap { it.node.descendantFolders }
            .any { !it.service.canDelete() }

        val canEdit = canWrite && !hasCanNotEditFolders
        val canDelete = canWrite && !hasCanNotDeleteFolders

        val actions = buildContextItems {
            section {
                // An option to view all the items that belong
                // to these folders.
                val ciphersCount = selectedNodes
                    .asSequence()
                    .flatMap { it.value.directCiphers }
                    .distinctBy { it.id }
                    .count()
                if (ciphersCount > 0) {
                    this += FlatItemAction(
                        icon = Icons.Outlined.KeyguardCipher,
                        title = Res.string.items.wrap(),
                        trailing = {
                            ChevronIcon()
                        },
                        onClick = onClick {
                            val folders = selectedNodes.values
                                .flatMap { it.node.directFolders }
                                .distinctBy { it.id }
                            val route = vaultRouteFactory.by(folders = folders)
                            val intent = NavigationIntent.NavigateToRoute(route)
                            navigate(intent)
                        },
                    )
                }
            }
            section {
                if (canEdit) {
                    this += FlatItemAction(
                        icon = Icons.Outlined.Edit,
                        title = Res.string.rename.wrap(),
                        onClick = onClick {
                            val nodes = selectedNodes.values
                                .map { it.node }
                            onRename(nodes)
                        },
                    )
                    if (selectedNodes.size > 1) {
                        val directFolders = selectedNodes.values
                            .flatMap { it.node.directFolders }
                            .distinctBy { it.id }
                        val folderAccountId = directFolders.firstOrNull()?.accountId
                        val singleAccount = folderAccountId != null &&
                                directFolders.all { it.accountId == folderAccountId } &&
                                directFolders.size >= selectedNodes.size
                        if (singleAccount) {
                            val folderName = selectedNodes.values
                                .maxBy { it.directCiphers.size }
                                .node
                                .name
                            val selectedFolderIds = directFolders
                                .mapTo(mutableSetOf()) { it.id }
                            this += FlatItemAction(
                                icon = Icons.Outlined.Merge,
                                title = Res.string.folder_action_merge_title.wrap(),
                                onClick = onClick {
                                    onMerge(folderName, selectedFolderIds)
                                },
                            )
                        }
                    }
                }
                if (canDelete) {
                    val selectedFolderIds = selectedNodes.values
                        .flatMap { it.node.descendantFolderIds }
                        .toSet()
                    val hasCiphers = selectedNodes.any { it.value.subtreeCiphers.isNotEmpty() }
                    this += FlatItemAction(
                        icon = Icons.Outlined.Delete,
                        title = Res.string.delete.wrap(),
                        onClick = onClick {
                            onDelete(selectedFolderIds, hasCiphers)
                        },
                    )
                }
            }
        }

        Selection(
            count = selectedNodes.size,
            actions = actions.toImmutableList(),
            onSelectAll = selectionHandle::setSelection
                .partially1(nodeKeys)
                .takeIf {
                    nodeKeys.size > selectedNodeKeys.size
                },
            onClear = selectionHandle::clearSelection,
        )
    }
    val contentFlow = combine(
        nodesWithCiphersFlow,
        selectedNodesWithCiphersFlow,
        getCanWrite(),
    ) { (tree, nodesWithCiphers), selectedNodes, canWrite ->
        val selecting = selectedNodes.isNotEmpty()
        val items = nodesWithCiphers
            .map { nodeWithCiphers ->
                val node = nodeWithCiphers.node
                val selected = node.key in selectedNodes

                val canEdit = canWrite && node.descendantFolders.all { it.service.canEdit() }
                val canDelete = canWrite && node.descendantFolders.all { it.service.canDelete() }

                val actions = buildContextItems {
                    section {
                        // An option to view all the items that belong
                        // to this cipher.
                        if (!node.deleted &&
                            node.directFolders.isNotEmpty() &&
                            nodeWithCiphers.directCiphers.isNotEmpty()
                        ) {
                            this += FlatItemAction(
                                icon = Icons.Outlined.KeyguardCipher,
                                title = Res.string.items.wrap(),
                                trailing = {
                                    ChevronIcon()
                                },
                                onClick = onClick {
                                    val route = vaultRouteFactory.by(
                                        folders = node.directFolders,
                                    )
                                    val intent = NavigationIntent.NavigateToRoute(route)
                                    navigate(intent)
                                },
                            )
                        }
                    }
                    section {
                        if (!node.deleted && canEdit) {
                            this += FlatItemAction(
                                icon = Icons.Outlined.Edit,
                                title = Res.string.rename.wrap(),
                                onClick = onClick {
                                    onRename(
                                        nodes = listOf(node),
                                    )
                                },
                            )
                        }
                        if (!node.deleted && canDelete) {
                            this += FlatItemAction(
                                icon = Icons.Outlined.Delete,
                                title = Res.string.delete.wrap(),
                                onClick = onClick {
                                    onDelete(
                                        folderIds = node.descendantFolderIds,
                                        hasCiphers = nodeWithCiphers.subtreeCiphers.isNotEmpty(),
                                    )
                                },
                            )
                        }
                    }
                }
                FoldersState.Content.Item.Folder(
                    key = node.key,
                    title = node.name,
                    ciphers = nodeWithCiphers.directCiphers.size,
                    folders = node.visibleChildFolderCount,
                    selecting = selecting,
                    selected = selected,
                    synced = node.descendantFolders.all { it.synced },
                    failed = node.descendantFolders
                        .any { it.service.error.exists(it.revisionDate) },
                    hasChildren = node.hasVisibleChildren,
                    onViewItemsClick = if (!node.deleted && node.directFolders.isNotEmpty()) {
                        onClick {
                            val route = vaultRouteFactory.by(
                                folders = node.directFolders,
                            )
                            val intent = NavigationIntent.NavigateToRoute(route)
                            navigate(intent)
                        }
                    } else {
                        null
                    },
                    actions = actions.toImmutableList(),
                    onClick = when {
                        node.deleted -> null
                        selecting -> selectionHandle::toggleSelection.partially1(node.key)
                        node.hasVisibleChildren -> onClick {
                            val route = foldersRouteFactory.create(
                                args = args.copy(
                                    parent = node.anchor,
                                ),
                            )
                            val intent = NavigationIntent.NavigateToRoute(route)
                            navigate(intent)
                        }
                        node.directFolders.isNotEmpty() &&
                                nodeWithCiphers.directCiphers.isNotEmpty() -> onClick {
                            val route = vaultRouteFactory.by(
                                folders = node.directFolders,
                            )
                            val intent = NavigationIntent.NavigateToRoute(route)
                            navigate(intent)
                        }
                        else -> null
                    },
                    onLongClick = if (node.deleted || selecting) {
                        null
                    } else {
                        selectionHandle::toggleSelection.partially1(node.key)
                    },
                )
            }
            .toList()
        val itemsReShaped = items
            .mapListShape()
            .toImmutableList()
        FoldersState.Content(
            items = itemsReShaped,
        )
            .let { tree to it }
    }
    return combine(
        selectionFlow,
        contentFlow,
    ) { selection, (tree, content) ->
        FoldersState(
            title = tree.title
                ?: translate(Res.string.folders),
            text = if (args.parent == null) {
                translate(Res.string.account)
            } else {
                translate(Res.string.folders)
            },
            selection = selection,
            content = Loadable.Ok(content),
            onAdd = onClick {
                onAdd()
            }.takeUnless { selection != null || (args.parent == null && accountId == null) },
        )
    }
}
