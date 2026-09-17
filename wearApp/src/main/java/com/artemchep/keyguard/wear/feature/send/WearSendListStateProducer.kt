package com.artemchep.keyguard.wear.feature.send

import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import arrow.core.partially1
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.nullable
import com.artemchep.keyguard.common.io.parallelSearch
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.iconImageVector
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetSends
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.SendToolbox
import com.artemchep.keyguard.common.usecase.SupervisorRead
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.auth.keepass.KeePassLoginRoute
import com.artemchep.keyguard.feature.decorator.forEachWithDecorUniqueSectionsOnly
import com.artemchep.keyguard.feature.generator.history.mapLatestScoped
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.home.vault.search.find
import com.artemchep.keyguard.feature.home.vault.search.findAlike
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut
import com.artemchep.keyguard.feature.navigation.keyboard.interceptKeyEvents
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.debounceSearch
import com.artemchep.keyguard.feature.send.ComparatorHolder
import com.artemchep.keyguard.feature.send.ScrollPositionState
import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.feature.send.SendRoute
import com.artemchep.keyguard.feature.send.add.SendAddRoute
import com.artemchep.keyguard.feature.send.createSendListSortDecorator
import com.artemchep.keyguard.feature.send.createSendSortItemsFlow
import com.artemchep.keyguard.feature.send.search.LastDeletedSendSort
import com.artemchep.keyguard.feature.send.search.OurFilterResult
import com.artemchep.keyguard.feature.send.search.SendSort
import com.artemchep.keyguard.feature.send.search.createFilter
import com.artemchep.keyguard.feature.send.search.createFilterItemsFlow
import com.artemchep.keyguard.feature.send.search.filter.FilterSendHolder
import com.artemchep.keyguard.feature.send.toVaultListItem
import com.artemchep.keyguard.feature.send.util.SendUtil
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.SyncIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.selection.selectionHandle
import kotlin.time.measureTimedValue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import org.koin.compose.currentKoinScope

@Composable
fun wearSendListScreenState(
    args: SendRoute.Args,
): WearSendListState = with(currentKoinScope()) {
    wearSendListScreenState(
        args = args,
        getAccounts = get(),
        getSends = get(),
        getProfiles = get(),
        getAppIcons = get(),
        getWebsiteIcons = get(),
        dateFormatter = get(),
        clipboardService = get(),
    )
}

@Composable
fun wearSendListScreenState(
    args: SendRoute.Args,
    getAccounts: GetAccounts,
    getSends: GetSends,
    getProfiles: GetProfiles,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
    dateFormatter: DateFormatter,
    clipboardService: ClipboardService,
): WearSendListState = produceScreenState(
    key = "send_list",
    initial = WearSendListState(),
    args = arrayOf(
        getAccounts,
        dateFormatter,
        clipboardService,
    ),
) {
    val copy = copier()
    val ciphersRawFlow = filterHiddenProfiles(
        getProfiles = getProfiles,
        getSends = getSends,
        filter = null,
    )

    val cipherSink = EventFlow<DSend>()

    val itemSink = mutablePersistedFlow("lole") { "" }

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
        val sort = args.sort
        if (sort != null) {
            ComparatorHolder(
                comparator = sort,
            )
        } else {
            sortDefault
        }
    }

    var scrollPositionKey: Any? = null
    val scrollPositionSink = mutablePersistedFlow<ScrollPositionState>("scroll_state") { ScrollPositionState() }

    val filterResult = createFilter()

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
                    val selectableFlow = flowOf(
                        SelectableItemState(
                            selecting = false,
                            selected = false,
                            can = true, // clickable
                            onClick = null,
                            onLongClick = null,
                        ),
                    )
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
                    item
                }
                .run {
                    val filter = args.filter
                    if (filter != null) {
                        val ciphers = map { it.source }
                        val predicate = filter.prepare(ciphers)
                        this
                            .filter { predicate(it.source) }
                    } else {
                        this
                    }
                }
            items
        }
        .flowOn(Dispatchers.Default)
        .shareIn(this, SharingStarted.WhileSubscribed(5000L), replay = 1)

    val comparatorsListFlow = createSendSortItemsFlow(sortSink)

    data class Rev<T>(
        val count: Int,
        val list: List<T>,
        val revision: Int = 0,
    )

    val ciphersFilteredFlow = createFilteredSendsFlow(
        ciphersFlow = ciphersFlow,
        orderFlow = sortSink,
        filterFlow = filterResult.filterFlow,
        dateFormatter = dateFormatter,
    )
        .map {
            Rev(
                count = it.count,
                list = it.list,
                revision = (it.filterConfig?.id ?: 0) xor
                        (it.orderConfig?.hashCode() ?: 0),
            )
        }
        .flowOn(Dispatchers.Default)
        .shareIn(this, SharingStarted.WhileSubscribed(), replay = 1)

    val filterListFlow = createFilterItemsFlow(
        outputGetter = { it.source },
        outputFlow = ciphersFilteredFlow
            .map { state ->
                state.list.mapNotNull { it as? SendItem.Item }
            },
        profileFlow = getProfiles(),
        cipherGetter = {
            it.source
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

    val itemsFlow = ciphersFilteredFlow
        .map { ciphers ->
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

            val a = object : WearSendListState.Content.Items.Revision.Mutable<Pair<Int, Int>> {
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
            val b = object : WearSendListState.Content.Items.Revision.Mutable<Int> {
                override val value: Int
                    get() = a.value.first
            }
            val c = object : WearSendListState.Content.Items.Revision.Mutable<Int> {
                override val value: Int
                    get() = a.value.second
            }
            WearSendListState.Content.Items(
                onSelected = { key ->
                    itemSink.value = key.orEmpty()
                },
                revision = WearSendListState.Content.Items.Revision(
                    id = ciphers.revision,
                    firstVisibleItemIndex = b,
                    firstVisibleItemScrollOffset = c,
                    onScroll = { index, offset ->
                        if (localScrollPositionKey !== scrollPositionKey) {
                            return@Revision
                        }

                        val item = items.getOrNull(index)
                            ?: return@Revision
                        scrollPositionSink.value = ScrollPositionState(
                            id = item.id,
                            offset = offset,
                            revision = ciphers.revision,
                        )
                    },
                ),
                list = items.toPersistentList(),
                count = ciphers.count,
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
    ) { itemsContent, filters, (comparators, sort) ->
        val revision = filters.rev xor sort.hashCode()
        val content = itemsContent
            ?: WearSendListState.Content.Skeleton

        WearSendListState(
            revision = revision,
            filters = filters.items.toPersistentList(),
            sort = comparators.toPersistentList(),
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
            sideEffects = WearSendListState.SideEffects(cipherSink),
        )
    }
}

private data class FilteredList<T>(
    val count: Int,
    val list: List<T>,
    val orderConfig: ComparatorHolder? = null,
    val filterConfig: FilterSendHolder? = null,
)

private fun createFilteredSendsFlow(
    ciphersFlow: Flow<List<SendItem.Item>>,
    orderFlow: Flow<ComparatorHolder>,
    filterFlow: Flow<FilterSendHolder>,
    dateFormatter: DateFormatter,
) = ciphersFlow
    .map { items ->
        FilteredList(
            count = items.size,
            list = items,
        )
    }
    .combine(
        flow = orderFlow
            .map { orderConfig ->
                val orderComparator = Comparator<SendItem.Item> { aModel, bModel ->
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
                val ciphers = map { it.source }
                val predicate = filterConfig.filter.prepare(ciphers)
                filter { predicate(it.source) }
            }
        state.copy(
            list = filteredAllItems,
            filterConfig = filterConfig,
        )
    }
    .map { state ->
        val orderConfig = state.orderConfig
        val decorator = createSendListSortDecorator(
            orderConfig = orderConfig,
            itemCount = state.list.size,
            dateFormatter = dateFormatter,
        )

        val out = mutableListOf<SendItem>()
        state.list.forEachWithDecorUniqueSectionsOnly(
            decorator = decorator,
            tag = "SendList",
            provideItemId = SendItem::id,
        ) { item ->
            out += item
        }
        FilteredList(
            count = state.list.size,
            list = out.ifEmpty {
                listOf(SendItem.NoItems)
            },
            orderConfig = state.orderConfig,
            filterConfig = state.filterConfig,
        )
    }
