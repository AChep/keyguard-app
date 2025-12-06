package com.artemchep.keyguard.feature.urlblock

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.model.buildDocs
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.usecase.AddUrlBlock
import com.artemchep.keyguard.common.usecase.GetUrlBlocks
import com.artemchep.keyguard.common.usecase.RemoveUrlBlockById
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.short
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.mapListShape
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
import kotlin.time.Clock
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.collections.map

private class UrlBlockListUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun produceUrlBlockListState(
) = with(localDI().direct) {
    produceUrlBlockListState(
        addUrlBlock = instance(),
        removeUrlBlockById = instance(),
        getUrlBlocks = instance(),
    )
}

@Composable
fun produceUrlBlockListState(
    addUrlBlock: AddUrlBlock,
    removeUrlBlockById: RemoveUrlBlockById,
    getUrlBlocks: GetUrlBlocks,
): Loadable<UrlBlockListState> = produceScreenState(
    key = "urlblock_list",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val selectionHandle = selectionHandle("selection")

    suspend fun onEdit(entity: DGlobalUrlBlock?) {
        val nameKey = "name"
        val nameItem = ConfirmationRoute.Args.Item.StringItem(
            key = nameKey,
            value = entity?.name.orEmpty(),
            title = translate(Res.string.generic_name),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
            canBeEmpty = true,
        )

        val descriptionKey = "description"
        val descriptionItem = ConfirmationRoute.Args.Item.StringItem(
            key = descriptionKey,
            value = entity?.description.orEmpty(),
            title = translate(Res.string.description),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
            canBeEmpty = true,
        )

        val exposedKey = "exposed"
        val exposedItem = ConfirmationRoute.Args.Item.BooleanItem(
            key = exposedKey,
            value = entity?.enabled ?: true,
            title = translate(Res.string.urlblock_expose_title),
            text = translate(Res.string.urlblock_expose_text),
        )

        val enabledKey = "enabled"
        val enabledItem = ConfirmationRoute.Args.Item.BooleanItem(
            key = enabledKey,
            value = entity?.enabled ?: true,
            title = translate(Res.string.enabled),
        )

        val uriKey = "uri"
        val uriItem = ConfirmationRoute.Args.Item.StringItem(
            key = uriKey,
            value = entity?.uri.orEmpty(),
            title = translate(Res.string.uri),
            type = ConfirmationRoute.Args.Item.StringItem.Type.URI,
            canBeEmpty = false,
        )

        val modeKey = "mode"
        val modeEl = MatchDetection.entries
            .filter { type ->
                type != MatchDetection.Never &&
                        type != MatchDetection.Default
            }
            .map { type ->
                val typeTitle = translate(type.matchType.titleH())
                ConfirmationRoute.Args.Item.EnumItem.Item(
                    key = type.name,
                    title = typeTitle,
                )
            }
        val modeItem = ConfirmationRoute.Args.Item.EnumItem(
            key = modeKey,
            value = entity?.mode?.name
                ?: MatchDetection.Domain.name,
            items = modeEl,
            docs = MatchDetection.buildDocs(
                translatorScope = this,
            ),
        )

        val items2 = listOf(
            nameItem,
            descriptionItem,
            enabledItem,
            exposedItem,
            uriItem,
            modeItem,
        )
        val route = registerRouteResultReceiver(
            route = ConfirmationRoute(
                args = ConfirmationRoute.Args(
                    icon = icon(
                        main = Icons.Outlined.Block,
                        secondary = if (entity != null) {
                            Icons.Outlined.Edit
                        } else {
                            Icons.Outlined.Add
                        },
                    ),
                    title = translate(Res.string.urlblock_header_title),
                    items = items2,
                ),
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                val name = result.data[nameKey] as? String
                    ?: return@registerRouteResultReceiver
                val description = result.data[descriptionKey] as? String
                    ?: return@registerRouteResultReceiver
                val enabled = result.data[enabledKey] as? Boolean
                    ?: return@registerRouteResultReceiver
                val exposed = result.data[exposedKey] as? Boolean
                    ?: return@registerRouteResultReceiver
                val uri = result.data[uriKey] as? String
                    ?: return@registerRouteResultReceiver
                val mode = (result.data[modeKey] as? String)?.let(MatchDetection::valueOf)
                    ?: return@registerRouteResultReceiver
                val createdAt = Clock.System.now()
                val model = DGlobalUrlBlock(
                    id = entity?.id,
                    name = name,
                    description = description,
                    uri = uri,
                    mode = mode,
                    createdDate = createdAt,
                    enabled = enabled,
                    exposed = exposed,
                )
                addUrlBlock(model)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    suspend fun onNew() = onEdit(null)

    suspend fun onDuplicate(entity: DGlobalUrlBlock) {
        val createdAt = Clock.System.now()
        val model = entity.copy(
            id = null,
            createdDate = createdAt,
        )
        addUrlBlock(model)
            .launchIn(appScope)
    }

    suspend fun onDeleteByItems(
        items: List<DGlobalUrlBlock>,
    ) {
        val title = if (items.size > 1) {
            translate(Res.string.urlblock_delete_many_confirmation_title)
        } else {
            translate(Res.string.urlblock_delete_one_confirmation_title)
        }
        val message = items
            .joinToString(separator = "\n") { it.name }
        val intent = createConfirmationDialogIntent(
            icon = icon(Icons.Outlined.Delete),
            title = title,
            message = message,
        ) {
            val ids = items
                .mapNotNull { it.id }
                .toSet()
            removeUrlBlockById(ids)
                .launchIn(appScope)
        }
        navigate(intent)
    }

    val itemsRawFlow = getUrlBlocks()
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
                        title = Res.string.delete.wrap(),
                        onClick = onClick {
                            onDeleteByItems(
                                items = selectedItems,
                            )
                        },
                    )
                }
            }
            Selection(
                count = selectedItems.size,
                actions = actions,
                onSelectAll = if (selectedItems.size < allItems.size) {
                    val allIds = allItems
                        .asSequence()
                        .mapNotNull { it.id }
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
            val items = list
                .map {
                    val dropdown = buildContextItems {
                        section {
                            this += FlatItemAction(
                                icon = Icons.Outlined.Edit,
                                title = Res.string.edit.wrap(),
                                onClick = onClick {
                                    onEdit(
                                        entity = it,
                                    )
                                },
                            )
                            this += FlatItemAction(
                                icon = Icons.Outlined.CopyAll,
                                title = Res.string.duplicate.wrap(),
                                onClick = onClick {
                                    onDuplicate(
                                        entity = it,
                                    )
                                },
                            )
                            this += FlatItemAction(
                                icon = Icons.Outlined.Delete,
                                title = Res.string.delete.wrap(),
                                onClick = onClick {
                                    onDeleteByItems(
                                        items = listOf(it),
                                    )
                                },
                            )
                        }
                    }
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
                    val mode = translate(it.mode.matchType.titleH())
                    UrlBlockListState.Item(
                        key = it.id.orEmpty(),
                        title = it.name,
                        text = it.description,
                        uri = it.uri.let(::AnnotatedString),
                        mode = mode.let(::AnnotatedString),
                        icon = icon,
                        accentLight = it.accentColor.light,
                        accentDark = it.accentColor.dark,
                        active = it.enabled,
                        dropdown = dropdown,
                        selectableState = selectableStateFlow,
                    )
                }
            items
                .mapListShape()
                .toPersistentList()
        }
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the URL block list!"
            UrlBlockListUiException(
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
                UrlBlockListState.Content(
                    revision = 0,
                    items = items,
                    selection = selection,
                    primaryAction = onClick {
                        onNew()
                    },
                )
            }
        Loadable.Ok(contentOrException)
    }
    contentFlow
        .map { content ->
            val state = UrlBlockListState(
                content = content,
            )
            Loadable.Ok(state)
        }
}
