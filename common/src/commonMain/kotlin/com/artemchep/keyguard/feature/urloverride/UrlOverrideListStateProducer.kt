package com.artemchep.keyguard.feature.urloverride

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DGlobalUrlOverride
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.execute.ExecuteCommand
import com.artemchep.keyguard.common.usecase.AddUrlOverride
import com.artemchep.keyguard.common.usecase.GetUrlOverrides
import com.artemchep.keyguard.common.usecase.RemoveUrlOverrideById
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.home.settings.SettingsItem
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.short
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
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

private class UrlOverrideListUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun produceUrlOverrideListState(
) = with(localDI().direct) {
    produceUrlOverrideListState(
        addUrlOverride = instance(),
        removeUrlOverrideById = instance(),
        getUrlOverrides = instance(),
        executeCommand = instance(),
    )
}

@Composable
fun produceUrlOverrideListState(
    addUrlOverride: AddUrlOverride,
    removeUrlOverrideById: RemoveUrlOverrideById,
    getUrlOverrides: GetUrlOverrides,
    executeCommand: ExecuteCommand,
): Loadable<UrlOverrideListState> = produceScreenState(
    key = "urloverride_list",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val selectionHandle = selectionHandle("selection")

    suspend fun onEdit(entity: DGlobalUrlOverride?) {
        val nameKey = "name"
        val nameItem = ConfirmationRoute.Args.Item.StringItem(
            key = nameKey,
            value = entity?.name.orEmpty(),
            title = translate(Res.string.generic_name),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
            canBeEmpty = false,
        )

        val enabledKey = "enabled"
        val enabledItem = ConfirmationRoute.Args.Item.BooleanItem(
            key = enabledKey,
            value = entity?.enabled ?: true,
            title = translate(Res.string.enabled),
        )

        val regexKey = "regex"
        val regexItem = ConfirmationRoute.Args.Item.StringItem(
            key = regexKey,
            value = entity?.regex?.toString().orEmpty(),
            title = translate(Res.string.regex),
            // A hint explains how would a user write a regex that
            // matches both HTTPS and HTTP schemes.
            hint = "^https?://.*",
            description = translate(Res.string.urloverride_regex_note),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Regex,
            canBeEmpty = false,
        )

        val commandKey = "command"
        val commandItem = ConfirmationRoute.Args.Item.StringItem(
            key = commandKey,
            value = entity?.command.orEmpty(),
            title = translate(Res.string.command),
            // A hint explains how would a user write a command that
            // converts all links to use the HTTPS scheme.
            hint = "https://{url:rmvscm}",
            type = ConfirmationRoute.Args.Item.StringItem.Type.Command,
            canBeEmpty = false,
        )

        val items2 = listOf(
            nameItem,
            regexItem,
            commandItem,
            enabledItem,
        )
        val route = registerRouteResultReceiver(
            route = ConfirmationRoute(
                args = ConfirmationRoute.Args(
                    icon = icon(
                        main = Icons.Outlined.Link,
                        secondary = if (entity != null) {
                            Icons.Outlined.Edit
                        } else {
                            Icons.Outlined.Add
                        },
                    ),
                    title = translate(Res.string.urloverride_header_title),
                    items = items2,
                ),
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                val name = result.data[nameKey] as? String
                    ?: return@registerRouteResultReceiver
                val enabled = result.data[enabledKey] as? Boolean
                    ?: return@registerRouteResultReceiver
                val regex = result.data[regexKey] as? String
                    ?: return@registerRouteResultReceiver
                val placeholder = result.data[commandKey] as? String
                    ?: return@registerRouteResultReceiver
                val createdAt = Clock.System.now()
                val model = DGlobalUrlOverride(
                    id = entity?.id,
                    name = name,
                    regex = regex.toRegex(),
                    command = placeholder,
                    createdDate = createdAt,
                    enabled = enabled,
                )
                addUrlOverride(model)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    suspend fun onNew() = onEdit(null)

    suspend fun onDuplicate(entity: DGlobalUrlOverride) {
        val createdAt = Clock.System.now()
        val model = entity.copy(
            id = null,
            createdDate = createdAt,
        )
        addUrlOverride(model)
            .launchIn(appScope)
    }

    suspend fun onDeleteByItems(
        items: List<DGlobalUrlOverride>,
    ) {
        val title = if (items.size > 1) {
            translate(Res.string.urloverride_delete_many_confirmation_title)
        } else {
            translate(Res.string.urloverride_delete_one_confirmation_title)
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
            removeUrlOverrideById(ids)
                .launchIn(appScope)
        }
        navigate(intent)
    }

    val itemsRawFlow = getUrlOverrides()
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
                    val regex = it.regex.toString().let(::AnnotatedString)
                    val command = it.command.let(::AnnotatedString)
                    UrlOverrideListState.Item(
                        key = it.id.orEmpty(),
                        title = it.name,
                        regex = regex,
                        command = command,
                        icon = icon,
                        accentLight = it.accentColor.light,
                        accentDark = it.accentColor.dark,
                        active = it.enabled,
                        dropdown = dropdown,
                        selectableState = selectableStateFlow,
                    )
                }
                items
                    .mapIndexed { index, item ->
                        val shapeState = getShapeState(
                            list = items,
                            index = index,
                            predicate = { el, offset ->
                                true
                            },
                        )
                        item.copy(
                            shapeState = shapeState,
                        )
                    }
                    .toPersistentList()
        }
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the URL override list!"
            UrlOverrideListUiException(
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
                UrlOverrideListState.Content(
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
            val state = UrlOverrideListState(
                content = content,
            )
            Loadable.Ok(state)
        }
}
