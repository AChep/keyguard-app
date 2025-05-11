package com.artemchep.keyguard.feature.filter.list

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import arrow.core.partially1
import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.service.filter.RemoveCipherFilterById
import com.artemchep.keyguard.common.service.filter.RenameCipherFilter
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.filter.CipherFiltersRoute
import com.artemchep.keyguard.feature.filter.util.CipherFilterUtil
import com.artemchep.keyguard.feature.filter.util.addShortcutActionOrNull
import com.artemchep.keyguard.feature.filter.view.CipherFilterViewDialogRoute
import com.artemchep.keyguard.feature.filter.view.CipherFilterViewFullRoute
import com.artemchep.keyguard.feature.generator.wordlist.util.WordlistUtil
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.short
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.keyboard.searchQueryShortcuts
import com.artemchep.keyguard.feature.search.search.IndexedModel
import com.artemchep.keyguard.feature.search.search.mapSearch
import com.artemchep.keyguard.feature.search.search.searchFilter
import com.artemchep.keyguard.feature.search.search.searchQueryHandle
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.selection.selectionHandle
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private class CipherFiltersListUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun produceCipherFiltersListState(
) = with(localDI().direct) {
    produceCipherFiltersListState(
        getCipherFilters = instance(),
        removeCipherFilterById = instance(),
        renameCipherFilter = instance(),
    )
}

@Composable
fun produceCipherFiltersListState(
    getCipherFilters: GetCipherFilters,
    removeCipherFilterById: RemoveCipherFilterById,
    renameCipherFilter: RenameCipherFilter,
): Loadable<CipherFiltersListState> = produceScreenState(
    key = "cipher_filter_list",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val selectionHandle = selectionHandle("selection")
    val queryHandle = searchQueryHandle("query")
    searchQueryShortcuts(queryHandle)
    val queryFlow = searchFilter(queryHandle) { model, revision ->
        CipherFiltersListState.Filter(
            revision = revision,
            query = model,
        )
    }

    fun onClick(model: DCipherFilter) {
        val route = CipherFilterViewFullRoute(
            args = CipherFilterViewDialogRoute.Args(
                model = model,
            ),
        )
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.PopById(CipherFiltersRoute.ROUTER_NAME),
                NavigationIntent.NavigateToRoute(route),
            ),
        )
        navigate(intent)
    }

    suspend fun List<DCipherFilter>.toItems(): List<CipherFiltersListState.Item> {
        return this
            .map { filter ->
                val id = filter.id
                val icon = if (filter.icon != null) {
                    VaultItemIcon.VectorIcon(
                        imageVector = filter.icon,
                    )
                } else {
                    VaultItemIcon.TextIcon.short(filter.name)
                }

                val selectableFlow = selectionHandle
                    .idsFlow
                    .map { selectedIds ->
                        SelectableItemStateRaw(
                            selecting = selectedIds.isNotEmpty(),
                            selected = id in selectedIds,
                        )
                    }
                    .distinctUntilChanged()
                    .map { raw ->
                        val onClick = if (raw.selecting) {
                            // lambda
                            selectionHandle::toggleSelection.partially1(id)
                        } else {
                            null
                        }
                        val onLongClick = if (raw.selecting) {
                            null
                        } else {
                            // lambda
                            selectionHandle::toggleSelection.partially1(id)
                        }
                        SelectableItemState(
                            selecting = raw.selecting,
                            selected = raw.selected,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    }
                val selectableStateFlow =
                    if (this.size >= 100) {
                        val sharing = SharingStarted.WhileSubscribed(1000L)
                        selectableFlow.persistingStateIn(this@produceScreenState, sharing)
                    } else {
                        selectableFlow.stateIn(this@produceScreenState)
                    }
                CipherFiltersListState.Item(
                    key = id,
                    icon = icon,
                    accentLight = filter.accentColor.light,
                    accentDark = filter.accentColor.dark,
                    name = AnnotatedString(filter.name),
                    data = filter,
                    selectableState = selectableStateFlow,
                    onClick = ::onClick
                        .partially1(filter),
                )
            }
    }

    val itemsRawFlow = getCipherFilters()
    val itemsFlow = itemsRawFlow
        .map { filters ->
            filters
                .toItems()
                // Index for the search.
                .map { item ->
                    IndexedModel(
                        model = item,
                        indexedText = IndexedText.invoke(item.name.text),
                    )
                }
        }
        .mapSearch(
            handle = queryHandle,
        ) { item, result ->
            // Replace the origin text with the one with
            // search decor applied to it.
            item.copy(name = result.highlightedText)
        }
    // Automatically de-select items
    // that do not exist.
    combine(
        itemsRawFlow,
        selectionHandle.idsFlow,
    ) { items, selectedItemIds ->
        val newSelectedItemIds = selectedItemIds
            .asSequence()
            .filter { id ->
                items.any { it.id == id }
            }
            .toSet()
        newSelectedItemIds.takeIf { it.size < selectedItemIds.size }
    }
        .filterNotNull()
        .onEach { ids -> selectionHandle.setSelection(ids) }
        .launchIn(screenScope)
    val selectionFlow = combine(
        itemsRawFlow,
        selectionHandle.idsFlow,
    ) { items, selectedItemIds ->
        val selectedItems = items
            .filter { it.id in selectedItemIds }
        items to selectedItems
    }
        .map { (allItems, selectedItems) ->
            if (selectedItems.isEmpty()) {
                return@map null
            }

            val actions = buildContextItems {
                if (selectedItems.size == 1) {
                    val selectedItem = selectedItems.first()
                    section {
                        this += CipherFilterUtil.addShortcutActionOrNull(
                            filter = selectedItem,
                        )
                    }
                    section {
                        this += FlatItemAction(
                            icon = Icons.Outlined.Edit,
                            title = Res.string.edit.wrap(),
                            onClick = onClick {
                                CipherFilterUtil.onRename(
                                    renameCipherFilter = renameCipherFilter,
                                    model = selectedItem,
                                )
                            },
                        )
                    }
                }
                section {
                    this += FlatItemAction(
                        leading = icon(Icons.Outlined.Delete),
                        title = Res.string.delete.wrap(),
                        onClick = onClick {
                            CipherFilterUtil.onDeleteByItems(
                                removeCipherFilterById = removeCipherFilterById,
                                items = selectedItems,
                            )
                        },
                    )
                }
            }
            Selection(
                count = selectedItems.size,
                actions = actions.toPersistentList(),
                onSelectAll = if (selectedItems.size < allItems.size) {
                    val allIds = allItems
                        .asSequence()
                        .map { it.id }
                        .toSet()
                    selectionHandle::setSelection
                        .partially1(allIds)
                } else {
                    null
                },
                onClear = selectionHandle::clearSelection,
            )
        }
        .stateIn(screenScope)
    val contentFlow = itemsFlow
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the cipher filters list!"
            CipherFiltersListUiException(
                msg = msg,
                cause = e,
            )
        }
        .map { result ->
            val contentOrException = result
                .map { (items, revision) ->
                    CipherFiltersListState.Content(
                        revision = revision,
                        items = items,
                    )
                }
            Loadable.Ok(contentOrException)
        }
    contentFlow
        .map { content ->
            val state = CipherFiltersListState(
                filter = queryFlow,
                selection = selectionFlow,
                content = content,
            )
            Loadable.Ok(state)
        }
}
