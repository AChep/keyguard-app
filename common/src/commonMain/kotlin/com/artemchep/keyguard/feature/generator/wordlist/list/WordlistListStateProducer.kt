package com.artemchep.keyguard.feature.generator.wordlist.list

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.model.DGeneratorWordlist
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.AddWordlist
import com.artemchep.keyguard.common.usecase.EditWordlist
import com.artemchep.keyguard.common.usecase.GetWordlists
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.common.usecase.RemoveWordlistById
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.generator.wordlist.WordlistsRoute
import com.artemchep.keyguard.feature.generator.wordlist.util.WordlistUtil
import com.artemchep.keyguard.feature.generator.wordlist.view.WordlistViewRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.short
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
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

private class WordlistListUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun produceWordlistListState(
) = with(localDI().direct) {
    produceWordlistListState(
        addWordlist = instance(),
        editWordlist = instance(),
        removeWordlistById = instance(),
        getWordlists = instance(),
        numberFormatter = instance(),
    )
}

@Composable
fun produceWordlistListState(
    addWordlist: AddWordlist,
    editWordlist: EditWordlist,
    removeWordlistById: RemoveWordlistById,
    getWordlists: GetWordlists,
    numberFormatter: NumberFormatter,
): Loadable<WordlistListState> = produceScreenState(
    key = "wordlist_list",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val selectionHandle = selectionHandle("selection")

    fun onView(entity: DGeneratorWordlist) {
        val route = WordlistViewRoute(
            args = WordlistViewRoute.Args(
                wordlistId = entity.idRaw,
            ),
        )
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.PopById(WordlistsRoute.ROUTER_NAME),
                NavigationIntent.NavigateToRoute(route),
            ),
        )
        navigate(intent)
    }

    val itemsRawFlow = getWordlists()
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
                    section {
                        val selectedItem = selectedItems.first()
                        this += FlatItemAction(
                            icon = Icons.Outlined.Edit,
                            title = translate(Res.strings.edit),
                            onClick = WordlistUtil::onRename
                                .partially1(this@produceScreenState)
                                .partially1(editWordlist)
                                .partially1(selectedItem),
                        )
                    }
                }
                section {
                    this += FlatItemAction(
                        leading = icon(Icons.Outlined.Delete),
                        title = translate(Res.strings.delete),
                        onClick = WordlistUtil::onDeleteByItems
                            .partially1(this@produceScreenState)
                            .partially1(removeWordlistById)
                            .partially1(selectedItems),
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
    val itemsFlow = itemsRawFlow
        .map { list ->
            list
                .map {
                    val icon = VaultItemIcon.TextIcon.short(it.name)

                    val selectableFlow = selectionHandle
                        .idsFlow
                        .map { selectedIds ->
                            SelectableItemStateRaw(
                                selecting = selectedIds.isNotEmpty(),
                                selected = it.id in selectedIds,
                            )
                        }
                        .distinctUntilChanged()
                        .map { raw ->
                            val onClick = if (raw.selecting) {
                                // lambda
                                selectionHandle::toggleSelection.partially1(it.id.orEmpty())
                            } else {
                                null
                            }
                            val onLongClick = if (raw.selecting) {
                                null
                            } else {
                                // lambda
                                selectionHandle::toggleSelection.partially1(it.id.orEmpty())
                            }
                            SelectableItemState(
                                selecting = raw.selecting,
                                selected = raw.selected,
                                onClick = onClick,
                                onLongClick = onLongClick,
                            )
                        }
                    val selectableStateFlow =
                        if (list.size >= 100) {
                            val sharing = SharingStarted.WhileSubscribed(1000L)
                            selectableFlow.persistingStateIn(this, sharing)
                        } else {
                            selectableFlow.stateIn(this)
                        }
                    val quantity = it.wordCount.toInt()
                    val counter = translate(
                        Res.plurals.word_count_plural,
                        quantity,
                        numberFormatter.formatNumber(quantity),
                    )
                    WordlistListState.Item(
                        key = it.id,
                        title = it.name,
                        counter = counter,
                        icon = icon,
                        wordlistId = it.idRaw,
                        accentLight = it.accentColor.light,
                        accentDark = it.accentColor.dark,
                        selectableState = selectableStateFlow,
                        onClick = ::onView
                            .partially1(it),
                    )
                }
                .toPersistentList()
        }
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the wordlist list!"
            WordlistListUiException(
                msg = msg,
                cause = e,
            )
        }
    val primaryActions = buildContextItems {
        section {
            this += FlatItemAction(
                leading = icon(Icons.Outlined.AttachFile),
                title = translate(Res.strings.wordlist_add_wordlist_via_file_title),
                onClick = WordlistUtil::onNewFromFile
                    .partially1(this@produceScreenState)
                    .partially1(addWordlist),
            )
            this += FlatItemAction(
                leading = icon(Icons.Outlined.KeyguardWebsite),
                title = translate(Res.strings.wordlist_add_wordlist_via_url_title),
                onClick = WordlistUtil::onNewFromUrl
                    .partially1(this@produceScreenState)
                    .partially1(addWordlist),
            )
        }
    }
    val contentFlow = combine(
        selectionFlow,
        itemsFlow,
    ) { selection, itemsResult ->
        val contentOrException = itemsResult
            .map { items ->
                WordlistListState.Content(
                    revision = 0,
                    items = items,
                    selection = selection,
                    primaryActions = primaryActions,
                )
            }
        Loadable.Ok(contentOrException)
    }
    contentFlow
        .map { content ->
            val state = WordlistListState(
                content = content,
            )
            Loadable.Ok(state)
        }
}
