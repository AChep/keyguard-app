package com.artemchep.keyguard.wear.feature.send

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
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.keepass.KeePassLoginRoute
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.decorator.ItemDecorator
import com.artemchep.keyguard.feature.decorator.ItemDecoratorDate
import com.artemchep.keyguard.feature.decorator.ItemDecoratorNone
import com.artemchep.keyguard.feature.decorator.ItemDecoratorTitle
import com.artemchep.keyguard.feature.decorator.forEachWithDecorUniqueSectionsOnly
import com.artemchep.keyguard.feature.generator.history.mapLatestScoped
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.home.vault.search.find
import com.artemchep.keyguard.feature.home.vault.search.findAlike
import com.artemchep.keyguard.feature.home.vault.util.AlphabeticalSortMinItemsSize
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut
import com.artemchep.keyguard.feature.navigation.keyboard.interceptKeyEvents
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.debounceSearch
import com.artemchep.keyguard.feature.send.ComparatorHolder
import com.artemchep.keyguard.feature.send.OhOhOh
import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.feature.send.SendRoute
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
import com.artemchep.keyguard.feature.send.toVaultListItem
import com.artemchep.keyguard.feature.send.util.SendUtil
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.KeyguardView
import com.artemchep.keyguard.ui.icons.SyncIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.selection.selectionHandle
import org.jetbrains.compose.resources.StringResource
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
import org.kodein.di.DirectDI
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.time.measureTimedValue

@Composable
fun wearSendListScreenState(
    args: SendRoute.Args,
): WearSendListState = with(localDI().direct) {
    wearSendListScreenState(
        directDI = this,
        args = args,
        getAccounts = instance(),
        getSends = instance(),
        getProfiles = instance(),
        getAppIcons = instance(),
        getWebsiteIcons = instance(),
        dateFormatter = instance(),
        clipboardService = instance(),
    )
}

@Composable
fun wearSendListScreenState(
    directDI: DirectDI,
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
    val copy = copy(clipboardService)
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
    val scrollPositionSink = mutablePersistedFlow<OhOhOh>("scroll_state") { OhOhOh() }

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
                        val predicate = filter.prepare(directDI, ciphers)
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

    val filterListFlow = ah(
        directDI = directDI,
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
                        scrollPositionSink.value = OhOhOh(
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

private data class FilteredBoo<T>(
    val count: Int,
    val list: List<T>,
    val orderConfig: ComparatorHolder? = null,
    val filterConfig: FilterSendHolder? = null,
)

private fun hahah(
    directDI: DirectDI,
    ciphersFlow: Flow<List<SendItem.Item>>,
    orderFlow: Flow<ComparatorHolder>,
    filterFlow: Flow<FilterSendHolder>,
    dateFormatter: DateFormatter,
) = ciphersFlow
    .map { items ->
        FilteredBoo(
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
                val predicate = filterConfig.filter.prepare(directDI, ciphers)
                filter { predicate(it.source) }
            }
        state.copy(
            list = filteredAllItems,
            filterConfig = filterConfig,
        )
    }
    .map { state ->
        val orderConfig = state.orderConfig
        val decorator: ItemDecorator<SendItem, SendItem.Item> = when {
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
        state.list.forEachWithDecorUniqueSectionsOnly(
            decorator = decorator,
            tag = "SendList",
            provideItemId = SendItem::id,
        ) { item ->
            out += item
        }
        FilteredBoo(
            count = state.list.size,
            list = out.ifEmpty {
                listOf(SendItem.NoItems)
            },
            orderConfig = state.orderConfig,
            filterConfig = state.filterConfig,
        )
    }
