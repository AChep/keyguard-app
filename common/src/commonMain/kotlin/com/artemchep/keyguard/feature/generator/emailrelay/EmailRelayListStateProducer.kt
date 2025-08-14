package com.artemchep.keyguard.feature.generator.emailrelay

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.relays.api.EmailRelay
import com.artemchep.keyguard.common.usecase.AddEmailRelay
import com.artemchep.keyguard.common.usecase.GetEmailRelays
import com.artemchep.keyguard.common.usecase.RemoveEmailRelayById
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsState
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.short
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.selection.selectionHandle
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Clock
import org.kodein.di.allInstances
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private class EmailRelayListUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun produceEmailRelayListState(
) = with(localDI().direct) {
    produceEmailRelayListState(
        emailRelays = allInstances(),
        addEmailRelay = instance(),
        removeEmailRelayById = instance(),
        getEmailRelays = instance(),
    )
}

@Composable
fun produceEmailRelayListState(
    emailRelays: List<EmailRelay>,
    addEmailRelay: AddEmailRelay,
    removeEmailRelayById: RemoveEmailRelayById,
    getEmailRelays: GetEmailRelays,
): Loadable<EmailRelayListState> = produceScreenState(
    key = "emailrelay_list",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val selectionHandle = selectionHandle("selection")

    suspend fun onEdit(model: EmailRelay, entity: DGeneratorEmailRelay?) {
        val keyName = "name"

        val items2 = model
            .schema
            .map { emailRelay ->
                val itemKey = emailRelay.key
                val itemValue = entity?.data?.get(itemKey).orEmpty()
                ConfirmationRoute.Args.Item.StringItem(
                    key = itemKey,
                    value = itemValue,
                    title = translate(emailRelay.value.title),
                    hint = emailRelay.value.hint?.let { translate(it) },
                    description = emailRelay.value.description?.let { translate(it) },
                    type = emailRelay.value.type,
                    canBeEmpty = emailRelay.value.canBeEmpty,
                )
            }
            .let {
                val out = mutableListOf<ConfirmationRoute.Args.Item<*>>()
                out += ConfirmationRoute.Args.Item.StringItem(
                    key = keyName,
                    value = entity?.name
                        ?: model.name,
                    title = translate(Res.string.generic_name),
                )
                out += it
                out
            }
        val route = registerRouteResultReceiver(
            route = ConfirmationRoute(
                args = ConfirmationRoute.Args(
                    icon = icon(
                        main = Icons.Outlined.Email,
                        secondary = if (entity != null) {
                            Icons.Outlined.Edit
                        } else {
                            Icons.Outlined.Add
                        },
                    ),
                    title = model.name,
                    subtitle = translate(Res.string.emailrelay_integration_title),
                    items = items2,
                    docUrl = model.docUrl,
                ),
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                val name = result.data[keyName] as? String
                    ?: return@registerRouteResultReceiver
                val createdAt = Clock.System.now()
                val data = model
                    .schema
                    .mapNotNull { entry ->
                        val value = result.data[entry.key] as? String
                            ?: return@mapNotNull null
                        entry.key to value
                    }
                    .toMap()
                    .toPersistentMap()
                val model = DGeneratorEmailRelay(
                    id = entity?.id,
                    name = name,
                    type = model.type,
                    data = data,
                    createdDate = createdAt,
                )
                addEmailRelay(model)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    suspend fun onNew(model: EmailRelay) = onEdit(model, null)

    suspend fun onDuplicate(entity: DGeneratorEmailRelay) {
        val createdAt = Clock.System.now()
        val model = entity.copy(
            id = null,
            createdDate = createdAt,
        )
        addEmailRelay(model)
            .launchIn(appScope)
    }

    suspend fun onDeleteByItems(
        items: List<DGeneratorEmailRelay>,
    ) {
        val title = if (items.size > 1) {
            translate(Res.string.emailrelay_delete_many_confirmation_title)
        } else {
            translate(Res.string.emailrelay_delete_one_confirmation_title)
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
            removeEmailRelayById(ids)
                .launchIn(appScope)
        }
        navigate(intent)
    }

    val primaryActions = emailRelays
        .map { emailRelay ->
            val key = emailRelay.type
            FlatItemAction(
                id = key,
                title = TextHolder.Value(emailRelay.name),
                onClick = onClick {
                    onNew(emailRelay)
                },
            )
        }
        .sortedWith(StringComparatorIgnoreCase { it.let { it.title as TextHolder.Value }.data })
        .toImmutableList()

    val itemsRawFlow = getEmailRelays()
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
                            onDeleteByItems(selectedItems)
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
                    val relay = emailRelays
                        .firstOrNull { r -> r.type == it.type }
                    val dropdown = buildContextItems {
                        section {
                            if (relay != null) {
                                this += FlatItemAction(
                                    icon = Icons.Outlined.Edit,
                                    title = Res.string.edit.wrap(),
                                    onClick = onClick {
                                        onEdit(
                                            model = relay,
                                            entity = it,
                                        )
                                    },
                                )
                            }
                            this += FlatItemAction(
                                icon = Icons.Outlined.CopyAll,
                                title = Res.string.duplicate.wrap(),
                                onClick = onClick {
                                    onDuplicate(it)
                                },
                            )
                            this += FlatItemAction(
                                icon = Icons.Outlined.Delete,
                                title = Res.string.delete.wrap(),
                                onClick = onClick {
                                    onDeleteByItems(listOf(it))
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
                    EmailRelayListState.Item(
                        key = it.id.orEmpty(),
                        title = it.name,
                        service = relay?.name ?: it.type,
                        icon = icon,
                        accentLight = it.accentColor.light,
                        accentDark = it.accentColor.dark,
                        dropdown = dropdown,
                        selectableState = selectableStateFlow,
                    )
                }
                .toList()
            val itemsReShaped = items
                .mapIndexed { index, item ->
                    val shapeState = getShapeState(
                        list = items,
                        index = index,
                        predicate = { el, offset ->
                            el is EmailRelayListState.Item
                        },
                    )
                    item.copy(
                        shapeState = shapeState,
                    )
                }
                .toImmutableList()
            itemsReShaped
        }
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the email relay list!"
            EmailRelayListUiException(
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
                EmailRelayListState.Content(
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
            val state = EmailRelayListState(
                content = content,
            )
            Loadable.Ok(state)
        }
}
