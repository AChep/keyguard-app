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
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.MergeFolderById
import com.artemchep.keyguard.common.usecase.RemoveFolderById
import com.artemchep.keyguard.common.usecase.RenameFolderById
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.core.store.bitwarden.exists
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
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
    data class FolderWithCiphers(
        val folder: DFolder,
        val ciphers: List<DSecret>,
    )

    val foldersComparator = Comparator { a: DFolder, b: DFolder ->
        a.name.compareTo(b.name, ignoreCase = true)
    }
    val foldersFilter = args.filter ?: DFilter.All
    val foldersFlow = getFolders()
        .map { folders ->
            val predicate = foldersFilter.prepareFolders(directDI, folders)
            folders
                .filter { predicate(it) }
                .sortedWith(foldersComparator)
        }
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)
    val foldersWithCiphersFolder = getCiphers()
        .map { ciphers ->
            ciphers
                .filter { it.deletedDate == null }
                .groupBy { it.folderId }
        }
        .combine(foldersFlow) { ciphersByFolder, folders ->
            folders
                .map { folder ->
                    val ciphers = ciphersByFolder[folder.id].orEmpty()
                    FolderWithCiphers(
                        folder = folder,
                        ciphers = ciphers,
                    )
                }
                .run {
                    if (args.empty) {
                        filter { it.ciphers.isEmpty() }
                    } else {
                        this
                    }
                }
        }

    val selectionHandle = selectionHandle("selection")
    val selectedFoldersWithCiphersFlow = foldersWithCiphersFolder
        .combine(selectionHandle.idsFlow) { foldersWithCiphers, selectedFolderIds ->
            selectedFolderIds
                .mapNotNull { selectedFolderId ->
                    val folderWithCiphers = foldersWithCiphers
                        .firstOrNull { it.folder.id == selectedFolderId }
                        ?: return@mapNotNull null
                    selectedFolderId to folderWithCiphers
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
        accountId
            ?: // Should not happen, we should not
            // call this function if the account id
            // is null.
            return
        val intent = createConfirmationDialogIntent(
            item = ConfirmationRoute.Args.Item.StringItem(
                key = "name",
                title = translate(Res.string.generic_name),
                // Folder must have a non-empty name!
                canBeEmpty = false,
            ),
            title = translate(Res.string.folder_action_create_title),
        ) { name ->
            val accountIdsToNames = mapOf(
                AccountId(accountId) to name,
            )
            addFolder(accountIdsToNames)
                .launchIn(appScope)
        }
        navigate(intent)
    }

    suspend fun onRename(
        folders: List<DFolder>,
    ) {
        val route = registerRouteResultReceiver(
            route = ConfirmationRoute(
                args = ConfirmationRoute.Args(
                    icon = icon(Icons.Outlined.Edit),
                    title = if (folders.size > 1) {
                        translate(Res.string.folder_action_change_names_title)
                    } else {
                        translate(Res.string.folder_action_change_name_title)
                    },
                    items = folders
                        .sortedWith(StringComparatorIgnoreCase { it.name })
                        .map { folder ->
                            ConfirmationRoute.Args.Item.StringItem(
                                key = folder.id,
                                value = folder.name,
                                title = folder.name,
                                type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
                                canBeEmpty = false,
                            )
                        },
                ),
            ),
        ) {
            if (it is ConfirmationResult.Confirm) {
                val folderIdsToNames = it.data
                    .mapValues { it.value as String }
                renameFolderById(folderIdsToNames)
                    .launchIn(appScope)
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

        val route = registerRouteResultReceiver(
            route = ConfirmationRoute(
                args = ConfirmationRoute.Args(
                    icon = icon(Icons.Outlined.Merge),
                    title = translate(Res.string.folder_action_merge_confirmation_title),
                    items = listOfNotNull(
                        folderNameItem,
                    ),
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

        val route = registerRouteResultReceiver(
            route = ConfirmationRoute(
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
        foldersFlow
            .map { folders ->
                folders
                    .map { it.id }
                    .toSet()
            }
            .distinctUntilChanged(),
        selectedFoldersWithCiphersFlow,
        getCanWrite(),
    ) { folderIds, selectedFolders, canWrite ->
        if (selectedFolders.isEmpty()) {
            return@combine null
        }

        val selectedFolderIds = selectedFolders.keys
        // Find folders that have some limitations
        val hasCanNotEditFolders = selectedFolders.values
            .any { !it.folder.service.canEdit() }
        val hasCanNotDeleteFolders = selectedFolders.values
            .any { !it.folder.service.canDelete() }

        val canEdit = canWrite && !hasCanNotEditFolders
        val canDelete = canWrite && !hasCanNotDeleteFolders

        val actions = buildContextItems {
            section {
                // An option to view all the items that belong
                // to these folders.
                val ciphersCount = selectedFolders
                    .asSequence()
                    .flatMap { it.value.ciphers }
                    .distinct()
                    .count()
                if (ciphersCount > 0) {
                    this += FlatItemAction(
                        icon = Icons.Outlined.KeyguardCipher,
                        title = Res.string.items.wrap(),
                        trailing = {
                            ChevronIcon()
                        },
                        onClick = onClick {
                            val folders = selectedFolders.values
                                .map { it.folder }
                            val route = VaultRoute.by(
                                translator = this@produceScreenState,
                                folders = folders,
                            )
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
                            val folders = selectedFolders.values
                                .map { it.folder }
                            onRename(folders)
                        },
                    )
                    if (selectedFolders.size > 1) {
                        val folderAccountId = selectedFolders.values.first().folder.accountId
                        val singleAccount = selectedFolders
                            .any { it.value.folder.accountId == folderAccountId }
                        if (singleAccount) {
                            val folderName =
                                selectedFolders.values.maxBy { it.ciphers.size }.folder.name
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
                    val hasCiphers = selectedFolders.any { it.value.ciphers.isNotEmpty() }
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
            count = selectedFolders.size,
            actions = actions.toImmutableList(),
            onSelectAll = selectionHandle::setSelection
                .partially1(folderIds)
                .takeIf {
                    folderIds.size > selectedFolderIds.size
                },
            onClear = selectionHandle::clearSelection,
        )
    }
    val contentFlow = combine(
        foldersWithCiphersFolder,
        selectedFoldersWithCiphersFlow,
        getCanWrite(),
    ) { foldersWithCiphers, selectedFolderIds, canWrite ->
        val selecting = selectedFolderIds.isNotEmpty()
        val items = foldersWithCiphers
            .map { folderWithCiphers ->
                val folder = folderWithCiphers.folder
                val ciphers = folderWithCiphers.ciphers
                val selected = folder.id in selectedFolderIds

                val canEdit = canWrite && folder.service.canEdit()
                val canDelete = canWrite && folder.service.canDelete()

                val actions = buildContextItems {
                    section {
                        // An option to view all the items that belong
                        // to this cipher.
                        if (!folder.deleted && ciphers.isNotEmpty()) {
                            this += FlatItemAction(
                                icon = Icons.Outlined.KeyguardCipher,
                                title = Res.string.items.wrap(),
                                trailing = {
                                    ChevronIcon()
                                },
                                onClick = onClick {
                                    val route = VaultRoute.by(
                                        translator = this@produceScreenState,
                                        folder = folder,
                                    )
                                    val intent = NavigationIntent.NavigateToRoute(route)
                                    navigate(intent)
                                },
                            )
                        }
                    }
                    section {
                        if (!folder.deleted && canEdit) {
                            this += FlatItemAction(
                                icon = Icons.Outlined.Edit,
                                title = Res.string.rename.wrap(),
                                onClick = onClick {
                                    onRename(
                                        folders = listOf(folder),
                                    )
                                },
                            )
                        }
                        if (!folder.deleted && canDelete) {
                            this += FlatItemAction(
                                icon = Icons.Outlined.Delete,
                                title = Res.string.delete.wrap(),
                                onClick = onClick {
                                    onDelete(
                                        folderIds = setOf(folder.id),
                                        hasCiphers = folderWithCiphers.ciphers.isNotEmpty(),
                                    )
                                },
                            )
                        }
                    }
                }
                FoldersState.Content.Item.Folder(
                    key = folder.id,
                    title = folder.name,
                    ciphers = folderWithCiphers.ciphers.size,
                    selecting = selecting,
                    selected = selected,
                    synced = folder.synced,
                    failed = folder.service.error.exists(folder.revisionDate),
                    actions = actions.toImmutableList(),
                    onClick = if (!folder.deleted && selecting) {
                        // lambda
                        selectionHandle::toggleSelection.partially1(folder.id)
                    } else {
                        null
                    },
                    onLongClick = if (folder.deleted || selecting) {
                        null
                    } else {
                        // lambda
                        selectionHandle::toggleSelection.partially1(folder.id)
                    },
                )
            }
            .toImmutableList()
        FoldersState.Content(
            items = items,
        )
    }
    combine(
        selectionFlow,
        contentFlow,
    ) { selection, content ->
        FoldersState(
            selection = selection,
            content = Loadable.Ok(content),
            onAdd = onClick {
                onAdd()
            }.takeUnless { selection != null || accountId == null },
        )
    }
}
