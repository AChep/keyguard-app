package com.artemchep.keyguard.feature.send

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import arrow.core.identity
import arrow.core.partially1
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.nullable
import com.artemchep.keyguard.common.io.parallelSearch
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.iconImageVector
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.CipherToolbox
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetSends
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.SendToolbox
import com.artemchep.keyguard.common.usecase.SupervisorRead
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.decorator.ItemDecorator
import com.artemchep.keyguard.feature.decorator.ItemDecoratorDate
import com.artemchep.keyguard.feature.decorator.ItemDecoratorNone
import com.artemchep.keyguard.feature.decorator.ItemDecoratorTitle
import com.artemchep.keyguard.feature.decorator.forEachWithDecorUniqueSectionsOnly
import com.artemchep.keyguard.feature.generator.history.mapLatestScoped
import com.artemchep.keyguard.feature.home.settings.accounts.AccountsRoute
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.LeAddRoute
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.home.vault.search.find
import com.artemchep.keyguard.feature.home.vault.search.findAlike
import com.artemchep.keyguard.feature.home.vault.util.AlphabeticalSortMinItemsSize
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.SEARCH_DEBOUNCE
import com.artemchep.keyguard.feature.send.add.SendAddRoute
import com.artemchep.keyguard.feature.send.search.AccessCountSendSort
import com.artemchep.keyguard.feature.send.search.AlphabeticalSendSort
import com.artemchep.keyguard.feature.send.search.LastDeletedSendSort
import com.artemchep.keyguard.feature.send.search.LastExpiredSendSort
import com.artemchep.keyguard.feature.send.search.LastModifiedSendSort
import com.artemchep.keyguard.feature.send.search.OurFilterResult
import com.artemchep.keyguard.feature.send.search.SendSort
import com.artemchep.keyguard.feature.send.search.SendSortItem
import com.artemchep.keyguard.feature.send.search.ah
import com.artemchep.keyguard.feature.send.search.createFilter
import com.artemchep.keyguard.feature.send.search.filter.FilterSendHolder
import com.artemchep.keyguard.feature.send.util.SendUtil
import com.artemchep.keyguard.leof
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.KeyguardView
import com.artemchep.keyguard.ui.icons.SyncIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.selection.selectionHandle
import org.jetbrains.compose.resources.StringResource
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.getString
import org.kodein.di.DirectDI
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.time.measureTimedValue

@LeParcelize
data class OhOhOh(
    val id: String? = null,
    val offset: Int = 0,
    val revision: Int = 0,
) : LeParcelable

@LeParcelize
@Serializable
data class ComparatorHolder(
    val comparator: SendSort,
    val reversed: Boolean = false,
    val favourites: Boolean = false,
) : LeParcelable {
    companion object {
        fun of(map: Map<String, Any?>): ComparatorHolder {
            return ComparatorHolder(
                comparator = SendSort.valueOf(map["comparator"].toString()) ?: AlphabeticalSendSort,
                reversed = map["reversed"].toString() == "true",
                favourites = map["favourites"].toString() == "true",
            )
        }
    }

    fun toMap() = mapOf(
        "comparator" to comparator.id,
        "reversed" to reversed,
        "favourites" to favourites,
    )
}

@Composable
fun sendListScreenState(
    args: SendRoute.Args,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
    mode: AppMode,
): SendListState = with(localDI().direct) {
    sendListScreenState(
        directDI = this,
        args = args,
        highlightBackgroundColor = highlightBackgroundColor,
        highlightContentColor = highlightContentColor,
        mode = mode,
        clearVaultSession = instance(),
        getAccounts = instance(),
        getCanWrite = instance(),
        getSends = instance(),
        getProfiles = instance(),
        getAppIcons = instance(),
        getWebsiteIcons = instance(),
        toolbox = instance(),
        queueSyncAll = instance(),
        syncSupervisor = instance(),
        dateFormatter = instance(),
        clipboardService = instance(),
    )
}

@Composable
fun sendListScreenState(
    directDI: DirectDI,
    args: SendRoute.Args,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
    mode: AppMode,
    clearVaultSession: ClearVaultSession,
    getAccounts: GetAccounts,
    getCanWrite: GetCanWrite,
    getSends: GetSends,
    getProfiles: GetProfiles,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
    toolbox: SendToolbox,
    queueSyncAll: QueueSyncAll,
    syncSupervisor: SupervisorRead,
    dateFormatter: DateFormatter,
    clipboardService: ClipboardService,
): SendListState = produceScreenState(
    key = "send_list",
    initial = SendListState(),
    args = arrayOf(
        getAccounts,
        dateFormatter,
        clipboardService,
    ),
) {
    val storage = kotlin.run {
        val disk = loadDiskHandle("vault.list")
        PersistedStorage.InDisk(disk)
    }

    val copy = copy(clipboardService)
    val ciphersRawFlow = filterHiddenProfiles(
        getProfiles = getProfiles,
        getSends = getSends,
        filter = null,
    )

    val querySink = mutablePersistedFlow("query") { "" }
    val queryState = mutableComposeState(querySink)

    val cipherSink = EventFlow<DSend>()

    val itemSink = mutablePersistedFlow("lole") { "" }

    val selectionHandle = selectionHandle("selection")
    // Automatically remove selection from ciphers
    // that do not exist anymore.
    ciphersRawFlow
        .onEach { ciphers ->
            val selectedItemIds = selectionHandle.idsFlow.value
            val filteredSelectedItemIds = selectedItemIds
                .filter { itemId ->
                    ciphers.any { it.id == itemId }
                }
                .toSet()
            if (filteredSelectedItemIds.size < selectedItemIds.size) {
                selectionHandle.setSelection(filteredSelectedItemIds)
            }
        }
        .launchIn(this)

    val canEditFlow = SendUtil.canEditFlow(
        profilesFlow = getProfiles(),
        canWriteFlow = getCanWrite(),
    )

    val showKeyboardSink = if (args.canAlwaysShowKeyboard) {
        mutablePersistedFlow(
            key = "keyboard",
            storage = storage,
        ) { false }
    } else {
        mutablePersistedFlow(
            key = "keyboard",
        ) { false }
    }
    val syncFlow = syncSupervisor
        .get(AccountTask.SYNC)
        .map { accounts ->
            accounts.isNotEmpty()
        }

    val sortDefault = ComparatorHolder(
        comparator = LastDeletedSendSort,
        reversed = true,
    )
    val sortSink = mutablePersistedFlow(
        key = "sort",
        serialize = { json, value ->
            value.toMap()
        },
        deserialize = { json, value ->
            ComparatorHolder.of(value)
        },
    ) {
        if (args.sort != null) {
            ComparatorHolder(
                comparator = args.sort,
            )
        } else {
            sortDefault
        }
    }

    var scrollPositionKey: Any? = null
    val scrollPositionSink = mutablePersistedFlow<OhOhOh>("scroll_state") { OhOhOh() }

    val filterResult = createFilter()
    val actionsFlow = kotlin.run {
        val actionAlwaysShowKeyboardFlow = showKeyboardSink
            .map { showKeyboard ->
                FlatItemAction(
                    leading = {
                        Icon(
                            Icons.Outlined.Keyboard,
                            null,
                        )
                    },
                    trailing = {
                        Switch(
                            checked = showKeyboard,
                            onCheckedChange = showKeyboardSink::value::set,
                        )
                    },
                    title = Res.string.vault_action_always_show_keyboard_title.wrap(),
                    onClick = showKeyboardSink::value::set.partially1(!showKeyboard),
                )
            }
        val actionSyncAccountsFlow = syncFlow
            .map { syncing ->
                FlatItemAction(
                    leading = {
                        SyncIcon(
                            rotating = syncing,
                        )
                    },
                    title = Res.string.vault_action_sync_vault_title.wrap(),
                    onClick = if (!syncing) {
                        // lambda
                        {
                            queueSyncAll()
                                .launchIn(appScope)
                        }
                    } else {
                        null
                    },
                )
            }
        val actionLockVaultItem = FlatItemAction(
            leading = {
                Icon(Icons.Outlined.Lock, null)
            },
            title = Res.string.vault_action_lock_vault_title.wrap(),
            onClick = {
                val reason = TextHolder.Res(Res.string.lock_reason_manually)
                clearVaultSession(LockReason.LOCK, reason)
                    .launchIn(appScope)
            },
        )
        val actionLockVaultFlow = flowOf(actionLockVaultItem)
        combine(
            actionAlwaysShowKeyboardFlow,
            actionSyncAccountsFlow,
            actionLockVaultFlow,
        ) { array ->
            array.toList().takeIf { args.canAlwaysShowKeyboard }.orEmpty()
        }
    }

    data class ConfigMapper(
        val appIcons: Boolean,
        val websiteIcons: Boolean,
    )

    val configFlow = combine(
        getAppIcons(),
        getWebsiteIcons(),
    ) { appIcons, websiteIcons ->
        ConfigMapper(
            appIcons = appIcons,
            websiteIcons = websiteIcons,
        )
    }.distinctUntilChanged()
    val ciphersFlow = combine(
        ciphersRawFlow,
        configFlow,
    ) { secrets, cfg -> secrets to cfg }
        .mapLatestScoped { (secrets, cfg) ->
            val items = secrets
                .map { secret ->
                    val selectableFlow = selectionHandle
                        .idsFlow
                        .map { ids ->
                            SelectableItemStateRaw(
                                selecting = ids.isNotEmpty(),
                                selected = secret.id in ids,
                            )
                        }
                        .distinctUntilChanged()
                        .map { raw ->
                            val toggle = selectionHandle::toggleSelection
                                .partially1(secret.id)
                            val onClick = toggle.takeIf { raw.selecting }
                            val onLongClick = toggle
                                .takeIf { !raw.selecting && raw.canSelect }
                            SelectableItemState(
                                selecting = raw.selecting,
                                selected = raw.selected,
                                can = raw.canSelect,
                                onClick = onClick,
                                onLongClick = onLongClick,
                            )
                        }
                    val openedStateFlow = itemSink
                        .map {
                            val isOpened = it == secret.id
                            SendItem.Item.OpenedState(isOpened)
                        }
                    val sharing = SharingStarted.WhileSubscribed(1000L)
                    val localStateFlow = combine(
                        selectableFlow,
                        openedStateFlow,
                    ) { selectableState, openedState ->
                        SendItem.Item.LocalState(
                            openedState,
                            selectableState,
                        )
                    }.persistingStateIn(this, sharing)
                    val item = secret.toVaultListItem(
                        copy = copy,
                        appIcons = cfg.appIcons,
                        websiteIcons = cfg.websiteIcons,
                        localStateFlow = localStateFlow,
                        dateFormatter = dateFormatter,
                        onClick = { actions ->
                            SendItem.Item.Action.Go(
                                onClick = cipherSink::emit.partially1(secret),
                            )
                        },
                    )
                    val indexed = mutableListOf<SearchToken>()
                    indexed += SearchToken(
                        priority = 2f,
                        value = secret.name,
                    )
                    if (secret.notes.isNotBlank()) {
                        indexed += SearchToken(
                            priority = 0.8f,
                            value = secret.notes,
                        )
                    }
                    IndexedModel(
                        model = item,
                        indexedText = IndexedText(item.title.text),
                        indexedOther = indexed,
                    )
                }
                .run {
                    if (args.filter != null) {
                        val ciphers = map { it.model.source }
                        val predicate = args.filter.prepare(directDI, ciphers)
                        this
                            .filter { predicate(it.model.source) }
                    } else {
                        this
                    }
                }
            items
        }
        .flowOn(Dispatchers.Default)
        .shareIn(this, SharingStarted.WhileSubscribed(5000L), replay = 1)

    fun createComparatorAction(
        id: String,
        title: StringResource,
        icon: ImageVector? = null,
        config: ComparatorHolder,
    ) = SendSortItem.Item(
        id = id,
        config = config,
        title = TextHolder.Res(title),
        icon = icon,
        onClick = {
            sortSink.value = config
        },
        checked = false,
    )

    data class Fuu(
        val item: SendSortItem.Item,
        val subItems: List<SendSortItem.Item>,
    )

    val cam = mapOf(
        AlphabeticalSendSort to Fuu(
            item = createComparatorAction(
                id = "title",
                icon = Icons.Outlined.SortByAlpha,
                title = Res.string.sortby_title_title,
                config = ComparatorHolder(
                    comparator = AlphabeticalSendSort,
                    favourites = true,
                ),
            ),
            subItems = listOf(
                createComparatorAction(
                    id = "title_normal",
                    title = Res.string.sortby_title_normal_mode,
                    config = ComparatorHolder(
                        comparator = AlphabeticalSendSort,
                        favourites = true,
                    ),
                ),
                createComparatorAction(
                    id = "title_rev",
                    title = Res.string.sortby_title_reverse_mode,
                    config = ComparatorHolder(
                        comparator = AlphabeticalSendSort,
                        reversed = true,
                        favourites = true,
                    ),
                ),
            ),
        ),
        AccessCountSendSort to Fuu(
            item = createComparatorAction(
                id = "access_count",
                icon = Icons.Outlined.KeyguardView,
                title = Res.string.sortby_access_count_title,
                config = ComparatorHolder(
                    comparator = AccessCountSendSort,
                ),
            ),
            subItems = listOf(
                createComparatorAction(
                    id = "access_count_normal",
                    title = Res.string.sortby_access_count_normal_mode,
                    config = ComparatorHolder(
                        comparator = AccessCountSendSort,
                    ),
                ),
                createComparatorAction(
                    id = "access_count_rev",
                    title = Res.string.sortby_access_count_reverse_mode,
                    config = ComparatorHolder(
                        comparator = AccessCountSendSort,
                        reversed = true,
                    ),
                ),
            ),
        ),
        LastModifiedSendSort to Fuu(
            item = createComparatorAction(
                id = "modify_date",
                icon = Icons.Outlined.CalendarMonth,
                title = Res.string.sortby_modification_date_title,
                config = ComparatorHolder(
                    comparator = LastModifiedSendSort,
                ),
            ),
            subItems = listOf(
                createComparatorAction(
                    id = "modify_date_normal",
                    title = Res.string.sortby_modification_date_normal_mode,
                    config = ComparatorHolder(
                        comparator = LastModifiedSendSort,
                    ),
                ),
                createComparatorAction(
                    id = "modify_date_rev",
                    title = Res.string.sortby_modification_date_reverse_mode,
                    config = ComparatorHolder(
                        comparator = LastModifiedSendSort,
                        reversed = true,
                    ),
                ),
            ),
        ),
        LastExpiredSendSort to Fuu(
            item = createComparatorAction(
                id = "expiration_date",
                icon = Icons.Outlined.CalendarMonth,
                title = Res.string.sortby_expiration_date_title,
                config = ComparatorHolder(
                    comparator = LastExpiredSendSort,
                ),
            ),
            subItems = listOf(
                createComparatorAction(
                    id = "expiration_date_normal",
                    title = Res.string.sortby_expiration_date_normal_mode,
                    config = ComparatorHolder(
                        comparator = LastExpiredSendSort,
                    ),
                ),
                createComparatorAction(
                    id = "expiration_date_rev",
                    title = Res.string.sortby_expiration_date_reverse_mode,
                    config = ComparatorHolder(
                        comparator = LastExpiredSendSort,
                        reversed = true,
                    ),
                ),
            ),
        ),
        LastDeletedSendSort to Fuu(
            item = createComparatorAction(
                id = "deletion_date",
                icon = Icons.Outlined.CalendarMonth,
                title = Res.string.sortby_deletion_date_title,
                config = ComparatorHolder(
                    comparator = LastDeletedSendSort,
                ),
            ),
            subItems = listOf(
                createComparatorAction(
                    id = "deletion_date_normal",
                    title = Res.string.sortby_deletion_date_normal_mode,
                    config = ComparatorHolder(
                        comparator = LastDeletedSendSort,
                    ),
                ),
                createComparatorAction(
                    id = "deletion_date_rev",
                    title = Res.string.sortby_deletion_date_reverse_mode,
                    config = ComparatorHolder(
                        comparator = LastDeletedSendSort,
                        reversed = true,
                    ),
                ),
            ),
        ),
    )

    val comparatorsListFlow = sortSink
        .map { orderConfig ->
            val mainItems = cam.values
                .map { it.item }
                .map { item ->
                    val checked = item.config.comparator == orderConfig.comparator
                    item.copy(checked = checked)
                }
            val subItems = cam[orderConfig.comparator]?.subItems.orEmpty()
                .map { item ->
                    val checked = item.config == orderConfig
                    item.copy(checked = checked)
                }

            val out = mutableListOf<SendSortItem>()
            out += mainItems
            if (subItems.isNotEmpty()) {
                out += SendSortItem.Section(
                    id = "sub_items_section",
                    text = TextHolder.Res(Res.string.options),
                )
                out += subItems
            }
            out
        }

    val queryTrimmedFlow = querySink.map { it to it.trim() }

    val queryIndexedFlow = queryTrimmedFlow
        .debounce(SEARCH_DEBOUNCE)
        .map { (_, queryTrimmed) ->
            if (queryTrimmed.isEmpty()) return@map null
            IndexedText(
                text = queryTrimmed,
            )
        }

    data class Rev<T>(
        val count: Int,
        val list: List<T>,
        val revision: Int = 0,
    )

    val ciphersFilteredFlow = hahah(
        directDI = directDI,
        ciphersFlow = ciphersFlow,
        orderFlow = sortSink,
        filterFlow = filterResult.filterFlow,
        queryFlow = queryIndexedFlow,
        dateFormatter = dateFormatter,
        highlightBackgroundColor = highlightBackgroundColor,
        highlightContentColor = highlightContentColor,
    )
        .map {
            Rev(
                count = it.count,
                list = it.list,
                revision = (it.filterConfig?.id ?: 0) xor (
                        it.queryConfig?.text?.hashCode()
                            ?: 0
                        ) xor (it.orderConfig?.hashCode() ?: 0),
            )
        }
        .flowOn(Dispatchers.Default)
        .shareIn(this, SharingStarted.WhileSubscribed(), replay = 1)

    val filterListFlow = ah(
        directDI = directDI,
        outputGetter = { it.source },
        outputFlow = ciphersFilteredFlow
            .map { state ->
                state.list.mapNotNull { it as? SendItem.Item }
            },
        profileFlow = getProfiles(),
        cipherGetter = {
            it.model.source
        },
        cipherFlow = ciphersFlow,
        input = filterResult,
    )
        .stateIn(this, SharingStarted.WhileSubscribed(), OurFilterResult())

    suspend fun createTypeAction(
        type: DSend.Type,
    ) = FlatItemAction(
        leading = icon(type.iconImageVector()),
        title = type.titleH().wrap(),
        onClick = {
            val route = SendAddRoute(
                args = SendAddRoute.Args(
                    type = type,
                ),
            )
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )

    val primaryActionsAll = buildContextItems {
        this += createTypeAction(
            type = DSend.Type.Text,
        )
        this += createTypeAction(
            type = DSend.Type.File,
        ).takeIf { !isRelease }
    }
    val primaryActionsFlow = kotlin.run {
        combine(
            canEditFlow,
            selectionHandle.idsFlow,
        ) { canEdit, selectedItemIds ->
            if (canEdit && selectedItemIds.isEmpty()) {
                primaryActionsAll
            } else {
                // No items
                persistentListOf()
            }
        }
    }

    val selectionFlow = SendUtil.selectionFlow(
        selectionHandle = selectionHandle,
        sendsFlow = ciphersRawFlow,
        canEditFlow = canEditFlow,
        toolbox = toolbox,
    )

    val itemsFlow = ciphersFilteredFlow
        .combine(selectionFlow) { ciphers, selection ->
            val items = ciphers.list

            @Suppress("UnnecessaryVariable")
            val localScrollPositionKey = items
            scrollPositionKey = localScrollPositionKey

            val lastScrollState = scrollPositionSink.value
            val (firstVisibleItemIndex, firstVisibleItemScrollOffset) =
                if (lastScrollState.revision == ciphers.revision) {
                    val index = items
                        .indexOfFirst { it.id == lastScrollState.id }
                        .takeIf { it >= 0 }
                    index?.let { it to lastScrollState.offset }
                } else {
                    null
                }
                // otherwise start with a first item
                    ?: (0 to 0)

            val a = object : SendListState.Content.Items.Revision.Mutable<Pair<Int, Int>> {
                override val value: Pair<Int, Int>
                    get() {
                        val lastScrollState = scrollPositionSink.value
                        val v = if (lastScrollState.revision == ciphers.revision) {
                            val index = items
                                .indexOfFirst { it.id == lastScrollState.id }
                                .takeIf { it >= 0 }
                            index?.let { it to lastScrollState.offset }
                        } else {
                            null
                        } ?: (firstVisibleItemIndex to firstVisibleItemScrollOffset)
                        return v
                    }
            }
            val b = object : SendListState.Content.Items.Revision.Mutable<Int> {
                override val value: Int
                    get() = a.value.first
            }
            val c = object : SendListState.Content.Items.Revision.Mutable<Int> {
                override val value: Int
                    get() = a.value.second
            }
            SendListState.Content.Items(
                onSelected = { key ->
                    itemSink.value = key.orEmpty()
                },
                revision = SendListState.Content.Items.Revision(
                    id = ciphers.revision,
                    firstVisibleItemIndex = b,
                    firstVisibleItemScrollOffset = c,
                    onScroll = { index, offset ->
                        if (localScrollPositionKey !== scrollPositionKey) {
                            return@Revision
                        }

                        val item = items.getOrNull(index)
                            ?: return@Revision
                        scrollPositionSink.value = OhOhOh(
                            id = item.id,
                            offset = offset,
                            revision = ciphers.revision,
                        )
                    },
                ),
                list = items,
                count = ciphers.count,
                selection = selection,
            )
        }

    val itemsNullableFlow = itemsFlow
        // First search might take some time, but we want to provide
        // initial state as fast as we can.
        .nullable()
        .persistingStateIn(this, SharingStarted.WhileSubscribed(), null)
    combine(
        itemsNullableFlow,
        filterListFlow,
        comparatorsListFlow
            .combine(sortSink) { a, b -> a to b },
        queryTrimmedFlow,
        getAccounts()
            .map { it.isNotEmpty() }
            .distinctUntilChanged(),
    ) { itemsContent, filters, (comparators, sort), (query, queryTrimmed), hasAccounts ->
        val revision = filters.rev xor queryTrimmed.hashCode() xor sort.hashCode()
        val content = if (hasAccounts) {
            itemsContent
                ?: SendListState.Content.Skeleton
        } else {
            SendListState.Content.AddAccount(
                onAddAccount = {
                    val route = AccountsRoute
                    navigate(NavigationIntent.NavigateToRoute(route))
                },
            )
        }
        val queryField = if (content !is SendListState.Content.AddAccount) {
            // We want to let the user search while the items
            // are still loading.
            TextFieldModel2(
                state = queryState,
                text = query,
                onChange = queryState::value::set,
            )
        } else {
            TextFieldModel2(
                mutableStateOf(""),
            )
        }

        SendListState(
            revision = revision,
            query = queryField,
            filters = filters.items,
            sort = comparators
                .takeIf { queryTrimmed.isEmpty() }
                .orEmpty(),
            saveFilters = null,
            clearFilters = filters.onClear,
            clearSort = if (sortDefault != sort) {
                {
                    sortSink.value = sortDefault
                }
            } else {
                null
            },
            content = content,
            sideEffects = SendListState.SideEffects(cipherSink),
        )
    }.combine(primaryActionsFlow) { state, actions ->
        state.copy(
            primaryActions = actions,
        )
    }.combine(actionsFlow) { state, actions ->
        state.copy(
            actions = actions,
        )
    }.combine(showKeyboardSink) { state, showKeyboard ->
        state.copy(
            showKeyboard = showKeyboard && state.query.onChange != null,
        )
    }
}

private data class SearchToken(
    val priority: Float,
    val tokens: List<String>,
) {
    companion object {
        operator fun invoke(
            priority: Float,
            value: String,
        ) = SearchToken(
            priority = priority,
            tokens = value.lowercase().split(" "),
        )
    }
}

private class IndexedModel<T>(
    val model: T,
    val indexedText: IndexedText,
    val indexedOther: List<SearchToken>,
)

private data class FilteredModel<T>(
    val model: T,
    val score: Float,
    val result: IndexedText.FindResult?,
)

private data class FilteredCiphers<T>(
    val list: List<T>,
    val revision: Int,
)

private data class FilteredBoo<T>(
    val count: Int,
    val list: List<T>,
    val preferredList: List<T>,
    val orderConfig: ComparatorHolder? = null,
    val filterConfig: FilterSendHolder? = null,
    val queryConfig: IndexedText? = null,
)

private data class Preferences(
    val appId: String? = null,
    val webDomain: String? = null,
)

private fun hahah(
    directDI: DirectDI,
    ciphersFlow: Flow<List<IndexedModel<SendItem.Item>>>,
    orderFlow: Flow<ComparatorHolder>,
    filterFlow: Flow<FilterSendHolder>,
    queryFlow: Flow<IndexedText?>,
    dateFormatter: DateFormatter,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
) = ciphersFlow
    .map { items ->
        FilteredBoo(
            count = items.size,
            list = items,
            preferredList = emptyList(),
        )
    }
    .combine(
        flow = orderFlow
            .map { orderConfig ->
                val orderComparator = Comparator<IndexedModel<SendItem.Item>> { a, b ->
                    val aModel = a.model
                    val bModel = b.model

                    var r = 0
                    if (r == 0) {
                        r = orderConfig.comparator.compare(aModel, bModel)
                        r = if (orderConfig.reversed) -r else r
                    }
                    if (r == 0) {
                        r = aModel.id.compareTo(bModel.id)
                        r = if (orderConfig.reversed) -r else r
                    }
                    r
                }
                orderConfig to orderComparator
            },
    ) { state, (orderConfig, orderComparator) ->
        val sortedAllItems = state.list.sortedWith(orderComparator)
        state.copy(
            list = sortedAllItems,
            orderConfig = orderConfig,
        )
    }
    .combine(
        flow = filterFlow,
    ) { state, filterConfig ->
        // Fast path: if the there are no filters, then
        // just return original list of items.
        if (filterConfig.state.isEmpty()) {
            return@combine state.copy(
                filterConfig = filterConfig,
            )
        }

        val filteredAllItems = state
            .list
            .run {
                val ciphers = map { it.model.source }
                val predicate = filterConfig.filter.prepare(directDI, ciphers)
                filter { predicate(it.model.source) }
            }
        val filteredPreferredItems = state
            .preferredList
            .run {
                val ciphers = map { it.model.source }
                val predicate = filterConfig.filter.prepare(directDI, ciphers)
                filter { predicate(it.model.source) }
            }
        state.copy(
            list = filteredAllItems,
            preferredList = filteredPreferredItems,
            filterConfig = filterConfig,
        )
    }
    .combine(queryFlow) { a, b -> a to b } // i want to use map latest
    .mapLatest { (state, query) ->
        if (query == null) {
            return@mapLatest FilteredBoo(
                count = state.list.size,
                list = state.list.map { it.model },
                preferredList = state.preferredList.map { it.model },
                orderConfig = state.orderConfig,
                filterConfig = state.filterConfig,
                queryConfig = state.queryConfig,
            )
        }

        val filteredAllItems = state.list.search(
            query = query,
            highlightBackgroundColor = highlightBackgroundColor,
            highlightContentColor = highlightContentColor,
        )
        val filteredPreferredItems = state.preferredList.search(
            query = query,
            highlightBackgroundColor = highlightBackgroundColor,
            highlightContentColor = highlightContentColor,
        )
        FilteredBoo(
            count = filteredAllItems.size,
            list = filteredAllItems,
            preferredList = filteredPreferredItems,
            orderConfig = state.orderConfig,
            filterConfig = state.filterConfig,
            queryConfig = query,
        )
    }
    .map { state ->
        val orderConfig = state.orderConfig
        val decorator: ItemDecorator<SendItem, SendItem.Item> = when {
            // Search does not guarantee meaningful order that we can
            // show in the section.
            state.queryConfig != null -> ItemDecoratorNone

            orderConfig?.comparator is AlphabeticalSendSort &&
                    // it looks ugly on small lists
                    state.list.size >= AlphabeticalSortMinItemsSize ->
                ItemDecoratorTitle<SendItem, SendItem.Item>(
                    selector = { it.title.text },
                    factory = { id, text ->
                        SendItem.Section(
                            id = id,
                            text = text,
                        )
                    },
                )

            orderConfig?.comparator is LastModifiedSendSort ->
                ItemDecoratorDate<SendItem, SendItem.Item>(
                    dateFormatter = dateFormatter,
                    selector = { it.revisionDate },
                    factory = { id, text ->
                        SendItem.Section(
                            id = id,
                            text = text,
                        )
                    },
                )

            orderConfig?.comparator is LastExpiredSendSort ->
                ItemDecoratorDate<SendItem, SendItem.Item>(
                    dateFormatter = dateFormatter,
                    selector = { it.source.expirationDate },
                    factory = { id, text ->
                        SendItem.Section(
                            id = id,
                            text = text,
                        )
                    },
                )

            orderConfig?.comparator is LastDeletedSendSort ->
                ItemDecoratorDate<SendItem, SendItem.Item>(
                    dateFormatter = dateFormatter,
                    selector = { it.source.deletedDate },
                    factory = { id, text ->
                        SendItem.Section(
                            id = id,
                            text = text,
                        )
                    },
                )

            else -> ItemDecoratorNone
        }

        val out = mutableListOf<SendItem>()
        state.preferredList.forEach { item ->
            out += item
        }
        if (
            state.preferredList.isNotEmpty() &&
            state.list.isNotEmpty()
        ) {
            val section = SendItem.Section(
                id = "preferred.end",
                text = "All items",
            )
            out += section
        }
        state.list.forEachWithDecorUniqueSectionsOnly(
            decorator = decorator,
            tag = "SendList",
            provideItemId = SendItem::id,
        ) { item ->
            out += item
        }
        FilteredBoo(
            count = state.list.size,
            list = out,
            preferredList = out,
            orderConfig = state.orderConfig,
            filterConfig = state.filterConfig,
            queryConfig = state.queryConfig,
        )
    }

private suspend fun List<IndexedModel<SendItem.Item>>.search(
    query: IndexedText,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
) = kotlin.run {
    // Fast path if there's nothing to search from.
    if (isEmpty()) {
        return@run this
            .map {
                it.model
            }
    }

    val queryComponents = query
        .components
        .map { it.lowercase }
    val timedValue = measureTimedValue {
        parallelSearch {
            val q = it.indexedOther
                .fold(0f) { y, x ->
                    y + findAlike(
                        source = x.tokens,
                        query = queryComponents,
                    )
                }.div(it.indexedOther.size.coerceAtLeast(1))
            val r = it.indexedText.find(
                query = query,
                colorBackground = highlightBackgroundColor,
                colorContent = highlightContentColor,
            )
            if (r == null && q <= 0.0001f) {
                return@parallelSearch null
            }
            FilteredModel(
                model = it.model,
                score = q + (r?.score ?: 0f),
                result = r,
            )
        }
            .sortedWith(
                compareBy(
                    { -it.score },
                ),
            )
            .map {
                it.result
                    ?: return@map it.model
                // Replace the origin text with the one with
                // search decor applied to it.
                it.model.copy(title = it.result.highlightedText)
            }
    }
    timedValue.value
}
