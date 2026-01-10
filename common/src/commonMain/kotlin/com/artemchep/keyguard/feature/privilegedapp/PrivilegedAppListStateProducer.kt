package com.artemchep.keyguard.feature.privilegedapp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.GetPrivilegedApps
import com.artemchep.keyguard.common.usecase.RemovePrivilegedAppById
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.short
import com.artemchep.keyguard.feature.localization.wrap
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
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.collections.map

private class PrivilegedAppListUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun producePrivilegedAppListState(
) = with(localDI().direct) {
    producePrivilegedAppListState(
        removePrivilegedAppById = instance(),
        getPrivilegedApps = instance(),
    )
}

@Composable
fun producePrivilegedAppListState(
    removePrivilegedAppById: RemovePrivilegedAppById,
    getPrivilegedApps: GetPrivilegedApps,
): Loadable<PrivilegedAppListState> = produceScreenState(
    key = "privilegedapp_list",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val selectionHandle = selectionHandle("selection")

    // Translations
    val sectionUserAppsText = translate(Res.string.privilegedapps_user_apps)
    val sectionCommunityAppsText = translate(Res.string.privilegedapps_community_apps)

    suspend fun onDeleteByItems(
        items: List<DPrivilegedApp>,
    ) {
        val title = if (items.size > 1) {
            translate(Res.string.privilegedapps_delete_many_confirmation_title)
        } else {
            translate(Res.string.privilegedapps_delete_one_confirmation_title)
        }
        val message = items
            .joinToString(separator = "\n") { it.name.orEmpty() }
        val intent = createConfirmationDialogIntent(
            icon = icon(Icons.Outlined.Delete),
            title = title,
            message = message,
        ) {
            val ids = items
                .mapNotNull { it.id }
                .toSet()
            removePrivilegedAppById(ids)
                .launchIn(appScope)
        }
        navigate(intent)
    }

    suspend fun DPrivilegedApp.toItem(): PrivilegedAppListState.Item.Content {
        val app = this
        val canEdit = app.source == DPrivilegedApp.Source.USER
        val dropdown = buildContextItems {
            section {
                if (canEdit) this += FlatItemAction(
                    icon = Icons.Outlined.Delete,
                    title = Res.string.delete.wrap(),
                    onClick = onClick {
                        onDeleteByItems(
                            items = listOf(app),
                        )
                    },
                )
            }
        }

        val selectableFlow = selectionHandle
            .idsFlow
            .map { selectedIds ->
                SelectableItemStateRaw(
                    selecting = selectedIds.isNotEmpty(),
                    selected = app.id in selectedIds,
                )
            }
            .distinctUntilChanged()
            .map { raw ->
                val onClick = if (raw.selecting) {
                    // lambda
                    selectionHandle::toggleSelection.partially1(app.id.orEmpty())
                } else {
                    null
                }
                val onLongClick = if (raw.selecting) {
                    null
                } else {
                    // lambda
                    selectionHandle::toggleSelection.partially1(app.id.orEmpty())
                }
                SelectableItemState(
                    selecting = raw.selecting,
                    selected = raw.selected,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
            }
        val selectableStateFlow =
            kotlin.run {
                val sharing = SharingStarted.WhileSubscribed(1000L)
                selectableFlow.persistingStateIn(this@produceScreenState, sharing)
            }
        return PrivilegedAppListState.Item.Content(
            key = app.id.orEmpty(),
            title = app.packageName,
            cert = app.certFingerprintSha256,
            dropdown = dropdown,
            selectableState = selectableStateFlow,
        )
    }

    suspend fun List<DPrivilegedApp>.toItems(
    ): List<PrivilegedAppListState.Item.Content> = this
        .map { app ->
            app.toItem()
        }

    val itemsRawFlow = getPrivilegedApps()
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

            val canEdit = selectedItems
                .all { it.source == DPrivilegedApp.Source.USER }

            val actions = buildContextItems {
                section {
                    if (canEdit) this += FlatItemAction(
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
            val userItems = list
                .filter { it.source == DPrivilegedApp.Source.USER }
                .toItems()
                .mapListShape()
            val communityItems = list
                .filter { it.source == DPrivilegedApp.Source.APP }
                .toItems()
                .mapListShape()
            sequence {
                if (userItems.isNotEmpty()) {
                    val sectionItem = PrivilegedAppListState.Item.Section(
                        key = "section.user",
                        name = sectionUserAppsText,
                    )
                    yield(sectionItem)
                }
                yieldAll(userItems)

                if (communityItems.isNotEmpty()) {
                    val sectionItem = PrivilegedAppListState.Item.Section(
                        key = "section.community",
                        name = sectionCommunityAppsText,
                    )
                    yield(sectionItem)
                }
                yieldAll(communityItems)
            }.toPersistentList()
        }
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the privileged app list!"
            PrivilegedAppListUiException(
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
                PrivilegedAppListState.Content(
                    revision = 0,
                    items = items,
                    selection = selection,
                    primaryAction = null,
                )
            }
        Loadable.Ok(contentOrException)
    }
    contentFlow
        .map { content ->
            val state = PrivilegedAppListState(
                content = content,
            )
            Loadable.Ok(state)
        }
}
