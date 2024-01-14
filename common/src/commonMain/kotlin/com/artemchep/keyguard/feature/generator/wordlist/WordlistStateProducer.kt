package com.artemchep.keyguard.feature.generator.wordlist

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AddWordlistRequest
import com.artemchep.keyguard.common.model.DGeneratorWordlist
import com.artemchep.keyguard.common.model.EditWordlistRequest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.AddWordlist
import com.artemchep.keyguard.common.usecase.EditWordlist
import com.artemchep.keyguard.common.usecase.GetWordlists
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.common.usecase.RemoveWordlistById
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.KeyguardWordlist
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

private class WordlistUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun produceWordlistState(
) = with(localDI().direct) {
    produceWordlistState(
        addWordlist = instance(),
        editWordlist = instance(),
        removeWordlistById = instance(),
        getWordlists = instance(),
        numberFormatter = instance(),
    )
}

@Composable
fun produceWordlistState(
    addWordlist: AddWordlist,
    editWordlist: EditWordlist,
    removeWordlistById: RemoveWordlistById,
    getWordlists: GetWordlists,
    numberFormatter: NumberFormatter,
): Loadable<WordlistState> = produceScreenState(
    key = "wordlist",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val selectionHandle = selectionHandle("selection")

    fun onEdit(entity: DGeneratorWordlist) {
        val nameKey = "name"
        val nameItem = ConfirmationRoute.Args.Item.StringItem(
            key = nameKey,
            value = entity.name,
            title = translate(Res.strings.generic_name),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
            canBeEmpty = false,
        )

        val items = listOfNotNull(
            nameItem,
        )
        val route = registerRouteResultReceiver(
            route = ConfirmationRoute(
                args = ConfirmationRoute.Args(
                    icon = icon(
                        main = Icons.Outlined.KeyguardWordlist,
                        secondary = Icons.Outlined.Edit,
                    ),
                    title = translate(Res.strings.wordlist_edit_wordlist_title),
                    items = items,
                    docUrl = null,
                ),
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                val name = result.data[nameKey] as? String
                    ?: return@registerRouteResultReceiver

                val request = EditWordlistRequest(
                    id = entity.idRaw,
                    name = name,
                )
                editWordlist(request)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    fun onNew() {
        val nameKey = "name"
        val nameItem = ConfirmationRoute.Args.Item.StringItem(
            key = nameKey,
            value = "",
            title = translate(Res.strings.generic_name),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
            canBeEmpty = false,
        )

        val fileKey = "file"
        val fileItem = ConfirmationRoute.Args.Item.FileItem(
            key = fileKey,
            value = null,
            title = translate(Res.strings.wordlist),
        )

        val items = listOfNotNull(
            nameItem,
            fileItem,
        )
        val route = registerRouteResultReceiver(
            route = ConfirmationRoute(
                args = ConfirmationRoute.Args(
                    icon = icon(
                        main = Icons.Outlined.KeyguardWordlist,
                        secondary = Icons.Outlined.Add,
                    ),
                    title = translate(Res.strings.wordlist_add_wordlist_title),
                    items = items,
                    docUrl = null,
                ),
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                val name = result.data[nameKey] as? String
                    ?: return@registerRouteResultReceiver
                val file = result.data[fileKey] as? ConfirmationRoute.Args.Item.FileItem.File
                    ?: return@registerRouteResultReceiver

                val wordlist = AddWordlistRequest.Wordlist.FromFile(
                    uri = file.uri,
                )
                val request = AddWordlistRequest(
                    name = name,
                    wordlist = wordlist,
                )
                addWordlist(request)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    fun onDeleteByItems(
        items: List<DGeneratorWordlist>,
    ) {
        val title = if (items.size > 1) {
            translate(Res.strings.wordlist_delete_many_confirmation_title)
        } else {
            translate(Res.strings.wordlist_delete_one_confirmation_title)
        }
        val message = items
            .joinToString(separator = "\n") { it.name }
        val intent = createConfirmationDialogIntent(
            icon = icon(Icons.Outlined.Delete),
            title = title,
            message = message,
        ) {
            val ids = items
                .map { it.idRaw }
                .toSet()
            removeWordlistById(ids)
                .launchIn(appScope)
        }
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
                section {
                    this += FlatItemAction(
                        leading = icon(Icons.Outlined.Delete),
                        title = translate(Res.strings.delete),
                        onClick = ::onDeleteByItems
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
                    val dropdown = buildContextItems {
                        section {
                            this += FlatItemAction(
                                icon = Icons.Outlined.Edit,
                                title = translate(Res.strings.edit),
                                onClick = ::onEdit
                                    .partially1(it),
                            )
                            this += FlatItemAction(
                                icon = Icons.Outlined.Delete,
                                title = translate(Res.strings.delete),
                                onClick = ::onDeleteByItems
                                    .partially1(listOf(it)),
                            )
                        }
                    }
                    val icon = VaultItemIcon.TextIcon(
                        run {
                            val words = it.name.split(" ")
                            if (words.size <= 1) {
                                return@run words.firstOrNull()?.take(2).orEmpty()
                            }

                            words
                                .take(2)
                                .joinToString("") { it.take(1) }
                        }.uppercase(),
                    )

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
                    WordlistState.Item(
                        key = it.id,
                        title = it.name,
                        counter = counter,
                        icon = icon,
                        accentLight = it.accentColor.light,
                        accentDark = it.accentColor.dark,
                        dropdown = dropdown,
                        selectableState = selectableStateFlow,
                    )
                }
                .toPersistentList()
        }
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the wordlist list!"
            WordlistUiException(
                msg = msg,
                cause = e,
            )
        }
    val contentFlow = combine(
        selectionFlow,
        itemsFlow,
    ) { selection, itemsResult ->
        val contentOrException = itemsResult
            .map { items ->
                WordlistState.Content(
                    revision = 0,
                    items = items,
                    selection = selection,
                    primaryAction = ::onNew,
                )
            }
        Loadable.Ok(contentOrException)
    }
    contentFlow
        .map { content ->
            val state = WordlistState(
                content = content,
            )
            Loadable.Ok(state)
        }
}
