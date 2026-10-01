package com.artemchep.keyguard.apple.send

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.feature.send.SendListState
import com.artemchep.keyguard.feature.send.SendRoute
import com.artemchep.keyguard.feature.send.search.SendSortItem
import com.artemchep.keyguard.feature.send.search.filter.SendFilterItem
import com.artemchep.keyguard.feature.send.sendListScreenStateProducer
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.filePickerResultOf
import com.artemchep.keyguard.apple.model.VaultFilterItemKind
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.apple.model.VaultFilterItemSnapshot
import com.artemchep.keyguard.apple.model.VaultListItemKind
import com.artemchep.keyguard.apple.model.VaultSortItemKind
import com.artemchep.keyguard.apple.model.VaultSortItemSnapshot
import com.artemchep.keyguard.apple.model.buildMenuActionSnapshots
import com.artemchep.keyguard.apple.model.buildSelectionActionSnapshots
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.flow.collect
import org.koin.core.scope.Scope

internal class SendListController(
    private val ctx: CoreContext,
) {
    /**
     * [KeyguardCore] late-binds this to the navigation stack's composed interceptor so a file drop's
     * `SendAddRoute` reaches the stack, which opens the native create sheet, instead of being dropped.
     */
    var navigationInterceptorProvider: (Scope) -> ((NavigationIntent) -> Boolean)? =
        { _ -> null }

    private var latestSendListState: SendListState? = null
    private var sendListFilterHandlers: Map<String, () -> Unit> = emptyMap()
    private var sendListSortHandlers: Map<String, () -> Unit> = emptyMap()
    private var sendListSelectionActionHandlers: Map<String, () -> Unit> = emptyMap()
    private var sendListActionHandlers: Map<String, () -> Unit> = emptyMap()

    fun observeSendList(
        onChange: (SendListSnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchSessionObserver(
            onLocked = {
                latestSendListState = null
                sendListFilterHandlers = emptyMap()
                sendListSortHandlers = emptyMap()
                sendListSelectionActionHandlers = emptyMap()
                sendListActionHandlers = emptyMap()
                onChange(SendListSnapshot.empty)
            },
        ) { state ->
            val interceptor = navigationInterceptorProvider(state.sessionKoin)
            val producerFlow = with(state.sessionKoin) {
                ctx.koin.newHeadlessStateFlowScope("sendlist", this@launchSessionObserver, interceptor)
                    .sendListScreenStateProducer(
                        args = SendRoute.Args(),
                        highlightBackgroundColor = Color.Transparent,
                        highlightContentColor = Color.Transparent,
                        mode = AppMode.Main,
                        clearVaultSession = get(),
                        getAccounts = get(),
                        getCanWrite = get(),
                        getSends = get(),
                        getProfiles = get(),
                        getAppIcons = get(),
                        getWebsiteIcons = get(),
                        toolbox = get(),
                        queueSyncAll = get(),
                        syncSupervisor = get(),
                        dateFormatter = get(),
                        clipboardService = get(),
                        bitwardenLoginRouteFactory = get(),
                        confirmationRouteFactory = get(),
                    )
            }
            producerFlow.throttleLatest().collect { sendListState ->
                val filterHandlers = LinkedHashMap<String, () -> Unit>()
                val sortHandlers = LinkedHashMap<String, () -> Unit>()
                val selectionHandlers = LinkedHashMap<String, () -> Unit>()
                val actionHandlers = LinkedHashMap<String, () -> Unit>()
                val snapshot = buildSendListSnapshot(
                    sendListState,
                    leContext,
                    filterHandlers,
                    sortHandlers,
                    selectionHandlers,
                    actionHandlers,
                )
                ctx.publishOnMain {
                    latestSendListState = sendListState
                    sendListFilterHandlers = filterHandlers
                    sendListSortHandlers = sortHandlers
                    sendListSelectionActionHandlers = selectionHandlers
                    sendListActionHandlers = actionHandlers
                    onChange(snapshot)
                }
            }
        }
    }

    fun setSendListQuery(text: String) {
        latestSendListState?.query?.onChange?.invoke(text)
    }

    fun invokeSendListFilter(id: String) {
        sendListFilterHandlers[id]?.invoke()
    }

    fun invokeSendListSort(id: String) {
        sendListSortHandlers[id]?.invoke()
    }

    fun clearSendListFilters() {
        latestSendListState?.clearFilters?.invoke()
    }

    fun clearSendListSort() {
        latestSendListState?.clearSort?.invoke()
    }

    fun toggleSendListSelection(itemId: String) {
        val content = latestSendListState?.content as? SendListState.Content.Items ?: return
        val item = content.list
            .firstOrNull { it is SendItem.Item && it.id == itemId } as? SendItem.Item
            ?: return
        val selectable = item.localStateFlow.value.selectableItemState
        (selectable.onClick ?: selectable.onLongClick)?.invoke()
    }

    fun invokeSendListSelectionAction(id: String) {
        sendListSelectionActionHandlers.invokeAction(id)
    }

    fun invokeSendListAction(id: String) {
        sendListActionHandlers.invokeAction(id)
    }

    fun clearSendListSelection() {
        val content = latestSendListState?.content as? SendListState.Content.Items ?: return
        content.selection?.onClear?.invoke()
    }

    fun dropFileOnSendList(uri: String, name: String?, size: Long) {
        latestSendListState?.onFileDrop?.invoke(filePickerResultOf(uri, name, size))
    }

    private suspend fun buildSendListSnapshot(
        state: SendListState,
        leContext: LeContext,
        filterHandlers: LinkedHashMap<String, () -> Unit>,
        sortHandlers: LinkedHashMap<String, () -> Unit>,
        selectionHandlers: LinkedHashMap<String, () -> Unit>,
        actionHandlers: LinkedHashMap<String, () -> Unit>,
    ): SendListSnapshot {
        val filters = state.filters.map { item ->
            when (item) {
                is SendFilterItem.Section -> {
                    item.onClick?.let { filterHandlers[item.id] = it }
                    VaultFilterItemSnapshot(
                        id = item.id,
                        kind = VaultFilterItemKind.SECTION,
                        sectionId = item.sectionId,
                        title = item.text,
                        text = null,
                        checked = false,
                        enabled = item.onClick != null,
                        expanded = item.expanded,
                        indent = 0,
                    )
                }

                is SendFilterItem.ChipItem -> {
                    item.onClick?.let { filterHandlers[item.id] = it }
                    VaultFilterItemSnapshot(
                        id = item.id,
                        kind = VaultFilterItemKind.ITEM,
                        sectionId = item.sectionId,
                        title = item.title,
                        text = item.text,
                        checked = item.checked,
                        enabled = item.enabled,
                        expanded = true,
                        indent = 0,
                    )
                }
            }
        }

        val sort = state.sort.map { item ->
            when (item) {
                is SendSortItem.Section -> VaultSortItemSnapshot(
                    id = item.id,
                    kind = VaultSortItemKind.SECTION,
                    title = textResource(item.text, leContext).orEmpty(),
                    checked = false,
                )

                is SendSortItem.Item -> {
                    item.onClick?.let { sortHandlers[item.id] = it }
                    VaultSortItemSnapshot(
                        id = item.id,
                        kind = VaultSortItemKind.ITEM,
                        title = textResource(item.title, leContext),
                        checked = item.checked,
                    )
                }
            }
        }

        val content = state.content
        val items = if (content is SendListState.Content.Items) {
            content.list.mapNotNull { it.toSendListItemSnapshot() }
        } else {
            emptyList()
        }

        val selection = (content as? SendListState.Content.Items)?.selection
        val selectionActions = buildSelectionActionSnapshots(selection?.actions, leContext, selectionHandlers)
        val listActions = buildMenuActionSnapshots(state.actions, "list", leContext, actionHandlers)

        return SendListSnapshot(
            loaded = content is SendListState.Content.Items,
            needsAccount = content is SendListState.Content.AddAccount,
            query = state.query.text,
            queryRevision = state.query.textRevision,
            itemsRevision = (content as? SendListState.Content.Items)?.revision?.id ?: 0,
            filters = filters,
            sort = sort,
            items = items,
            canClearFilters = state.clearFilters != null,
            canClearSort = state.clearSort != null,
            activeFilterCount = filters.count { it.kind == VaultFilterItemKind.ITEM && it.checked },
            totalCount = (content as? SendListState.Content.Items)?.count ?: 0,
            selectionCount = selection?.count ?: 0,
            selectionActions = selectionActions,
            listActions = listActions,
            canDropFile = state.onFileDrop != null,
            canCreateFileSend = state.canCreateFileSend,
        )
    }

    private fun SendItem.toSendListItemSnapshot(): SendListItemSnapshot? = when (this) {
        is SendItem.Item -> SendListItemSnapshot(
            id = id,
            kind = VaultListItemKind.ITEM,
            secretId = source.id,
            accountId = accountId,
            title = title.text,
            text = text,
        )

        is SendItem.Section -> SendListItemSnapshot(
            id = id,
            kind = VaultListItemKind.SECTION,
            secretId = null,
            accountId = null,
            title = text.orEmpty(),
            text = null,
        )

        is SendItem.NoItems -> SendListItemSnapshot(
            id = id,
            kind = VaultListItemKind.NO_ITEMS,
            secretId = null,
            accountId = null,
            title = "",
            text = null,
        )
    }
}
