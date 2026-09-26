package com.artemchep.keyguard.wear.feature.vault

import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import arrow.core.identity
import arrow.core.partially1
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.nullable
import com.artemchep.keyguard.common.model.CipherFilterContext
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.common.service.filter.AddCipherFilter
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetTags
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import com.artemchep.keyguard.common.usecase.SupervisorRead
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.generator.history.mapLatestScoped
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.ComparatorHolder
import com.artemchep.keyguard.feature.home.vault.screen.FilterParams
import com.artemchep.keyguard.feature.home.vault.screen.OurFilterResult
import com.artemchep.keyguard.feature.home.vault.screen.ScrollPositionState
import com.artemchep.keyguard.feature.home.vault.screen.VaultListState
import com.artemchep.keyguard.feature.home.vault.screen.createFilter
import com.artemchep.keyguard.feature.home.vault.screen.createFilterItemsFlow
import com.artemchep.keyguard.feature.home.vault.screen.createVaultListSortDecorator
import com.artemchep.keyguard.feature.home.vault.screen.createVaultSortItemsFlow
import com.artemchep.keyguard.feature.home.vault.screen.toVaultListItem
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordLastModifiedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordStrengthSort
import com.artemchep.keyguard.feature.largetype.LargeTypeRoute
import com.artemchep.keyguard.feature.largetype.LargeTypeRoute.Args
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRoute
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRouteFactory
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.wear.feature.auth.WearLoginMethodRoute
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.currentKoinScope

@Composable
internal fun wearVaultListScreenState(
    args: VaultRoute.Args,
): WearVaultListState = with(currentKoinScope()) {
    wearVaultListScreenState(
        filterContext = get(),
        addCipherFilter = get(),
        confirmationRouteFactory = get(),
        getCipherFilters = get(),
        args = args,
        deeplinkService = get(),
        getAccounts = get(),
        getProfiles = get(),
        getCanWrite = get(),
        getCiphers = get(),
        getFolders = get(),
        getTags = get(),
        getCollections = get(),
        getOrganizations = get(),
        getTotpCode = get(),
        getConcealFields = get(),
        getAppIcons = get(),
        getWebsiteIcons = get(),
        passkeyTargetCheck = get(),
        syncSupervisor = get(),
        dateFormatter = get(),
        clipboardService = get(),
        passkeysCredentialViewRouteFactory = get(),
    )
}

@Composable
internal fun wearVaultListScreenState(
    filterContext: CipherFilterContext,
    addCipherFilter: AddCipherFilter,
    confirmationRouteFactory: ConfirmationRouteFactory,
    getCipherFilters: GetCipherFilters,
    args: VaultRoute.Args,
    deeplinkService: DeeplinkService,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getCanWrite: GetCanWrite,
    getCiphers: GetCiphers,
    getFolders: GetFolders,
    getTags: GetTags,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
    getTotpCode: GetTotpCode,
    getConcealFields: GetConcealFields,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
    passkeyTargetCheck: PasskeyTargetCheck,
    syncSupervisor: SupervisorRead,
    dateFormatter: DateFormatter,
    clipboardService: ClipboardService,
    passkeysCredentialViewRouteFactory: PasskeysCredentialViewRouteFactory,
): WearVaultListState = produceScreenState(
    key = "vault_list",
    initial = WearVaultListState(),
    args = arrayOf(
        getAccounts,
        getCiphers,
        getTotpCode,
        dateFormatter,
        clipboardService,
    ),
) {
    val storage = run {
        val disk = loadDiskHandle("vault.list")
        PersistedStorage.InDisk(disk)
    }

    val fffFilter = args.filter ?: DFilter.All
//    val fffAccountId = DFilter
//        .findOne<DFilter.ById>(fffFilter) { f ->
//            f.what == DFilter.ById.What.ACCOUNT
//        }
//        ?.id
    val fffFolderId = DFilter
        .findOne<DFilter.ById>(fffFilter) { f ->
            f.what == DFilter.ById.What.FOLDER
        }
        ?.id
//    val fffCollectionId = DFilter
//        .findOne<DFilter.ById>(fffFilter) { f ->
//            f.what == DFilter.ById.What.COLLECTION
//        }
//        ?.id
//    val fffOrganizationId = DFilter
//        .findOne<DFilter.ById>(fffFilter) { f ->
//            f.what == DFilter.ById.What.ORGANIZATION
//        }
//        ?.id

    val copy = copier()

    val ciphersRawFlow = filterHiddenProfiles(
        getProfiles = getProfiles,
        getCiphers = getCiphers,
        filter = args.filter,
    )

    val cipherSink = EventFlow<DSecret>()

    val itemSink = mutablePersistedFlow("lole") { "" }

    val rememberSortSink = mutablePersistedFlow(
        key = "sort_persistent_enabled",
        storage = if (args.canAlwaysShowKeyboard) {
            storage
        } else PersistedStorage.InMemory,
    ) { false }

    val sortDefault = ComparatorHolder(
        comparator = AlphabeticalSort,
        favourites = true,
    )
    // Alternative sort sink that is stored on the
    // disk storage. Mirrored from the in-memory sink.
    val sortPersistentSink = mutablePersistedFlow(
        key = "sort_persistent",
        storage = storage,
        serialize = ComparatorHolder::serialize,
        deserialize = ComparatorHolder::deserialize,
    ) {
        sortDefault
    }
    val sortSink = mutablePersistedFlow(
        key = "sort",
        serialize = ComparatorHolder::serialize,
        deserialize = ComparatorHolder::deserialize,
    ) {
        val sort = args.sort
        if (sort != null) {
            ComparatorHolder(
                comparator = sort,
            )
        } else {
            if (rememberSortSink.value) {
                sortPersistentSink.value
            } else {
                sortDefault
            }
        }
    }
    // Copy the in-memory sorting method into
    // the persistent storage. We need it for
    // 'Remember sorting method' option to work.
    sortSink
        .onEach { value ->
            sortPersistentSink.value = value
        }
        .launchIn(screenScope)

    var scrollPositionKey: Any? = null
    val scrollPositionSink = mutablePersistedFlow<ScrollPositionState>("scroll_state") { ScrollPositionState() }

    val filterResult = createFilter(addCipherFilter, confirmationRouteFactory)

    data class ConfigMapper(
        val concealFields: Boolean,
        val appIcons: Boolean,
        val websiteIcons: Boolean,
        val canWrite: Boolean,
    )

    val configFlow = combine(
        getConcealFields(),
        getAppIcons(),
        getWebsiteIcons(),
        getCanWrite(),
    ) { concealFields, appIcons, websiteIcons, canWrite ->
        ConfigMapper(
            concealFields = concealFields,
            appIcons = appIcons,
            websiteIcons = websiteIcons,
            canWrite = canWrite,
        )
    }.distinctUntilChanged()
    val organizationsByIdFlow = getOrganizations()
        .map { organizations ->
            organizations
                .associateBy { it.id }
        }

    val ciphersFlow = combine(
        ciphersRawFlow,
        organizationsByIdFlow,
        configFlow,
    ) { secrets, organizationsById, cfg -> Triple(secrets, organizationsById, cfg) }
        .mapLatestScoped { (secrets, organizationsById, cfg) ->
            val items = secrets
                .filter {
                    val passesTrashFilter = when (args.trash) {
                        true -> it.deletedDate != null
                        false -> it.deletedDate == null
                        null -> true
                    }
                    val passesArchiveFilter = when (args.archive) {
                        true -> it.archivedDate != null
                        false -> it.archivedDate == null
                        null -> true
                    }
                    passesTrashFilter && passesArchiveFilter
                }
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
                            VaultItem2.Item.OpenedState(isOpened)
                        }
                    val sharing = SharingStarted.WhileSubscribed(1000L)
                    val localStateFlow = combine(
                        selectableFlow,
                        openedStateFlow,
                    ) { selectableState, openedState ->
                        VaultItem2.Item.LocalState(
                            openedState,
                            selectableState,
                        )
                    }.persistingStateIn(this, sharing)
                    val item = secret.toVaultListItem(
                        copy = copy,
                        translator = this@produceScreenState,
                        getTotpCode = getTotpCode,
                        concealFields = cfg.concealFields,
                        appIcons = cfg.appIcons,
                        websiteIcons = cfg.websiteIcons,
                        organizationsById = organizationsById,
                        localStateFlow = localStateFlow,
                        onClick = { actions ->
                            VaultItem2.Item.Action.Go(
                                onClick = { cipherSink.emit(secret) },
                            )
                        },
                        onClickAttachment = { attachment ->
                            // lambda
                            {
                                // Do nothing
                            }
                        },
                        onClickPasskey = { credential ->
                            // lambda
                            {
                                val route = passkeysCredentialViewRouteFactory.create(
                                    args = PasskeysCredentialViewRoute.Args(
                                        cipherId = secret.id,
                                        credentialId = credential.credentialId,
                                        model = credential,
                                    ),
                                )
                                val intent = NavigationIntent.NavigateToRoute(route)
                                navigate(intent)
                            }
                        },
                        onClickPassword = { credential ->
                            // lambda
                            {
                                val password = credential.password
                                    .orEmpty()
                                val route = LargeTypeRoute(
                                    args = Args(
                                        phrases = listOf(password),
                                        colorize = true,
                                    ),
                                )
                                val intent = NavigationIntent.NavigateToRoute(route)
                                navigate(intent)
                            }
                        },
                    )
                    item
                }
                .run {
                    val filter = args.filter
                    if (filter != null) {
                        val ciphers = map { it.source }
                        val predicate = filter.prepare(filterContext, ciphers)
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

    val comparatorsListFlow = createVaultSortItemsFlow(sortSink)

    data class Rev<T>(
        val count: Int,
        val list: List<T>,
        val revision: Int = 0,
    )

    val ciphersFilteredStateFlow = createFilteredCiphersFlow(
        filterContext = filterContext,
        ciphersFlow = ciphersFlow,
        orderFlow = sortSink,
        filterFlow = filterResult.filterFlow,
        dateFormatter = dateFormatter,
    )
        .shareIn(this, SharingStarted.WhileSubscribed(), replay = 1)

    val ciphersFilteredFlow = ciphersFilteredStateFlow
        .map {
            val keepOtp = it.filterConfig?.filter
                ?.let {
                    DFilter.findAny<DFilter.ByOtp>(it)
                } != null
            val keepAttachment = it.filterConfig?.filter
                ?.let {
                    DFilter.findAny<DFilter.ByAttachments>(it)
                } != null
            val keepPasskey = it.filterConfig?.filter
                ?.let {
                    DFilter.findAny<DFilter.ByPasskeys>(it)
                } != null
            val keepPassword = it.orderConfig
                ?.let {
                    // Regular password sort is not included here intentionally,
                    // because if that is selected then the password will be
                    // previewed as a section.
                    val sort = it.comparator
                    sort is PasswordLastModifiedSort ||
                            sort is PasswordStrengthSort
                } != false
            val l = if (keepOtp && keepPasskey && keepPassword) {
                it.list
            } else {
                it.list
                    .mapIndexed { index, item ->
                        when (item) {
                            is VaultItem2.Item -> {
                                val shapeState = getShapeState(
                                    list = it.list,
                                    index = index,
                                    predicate = { el, _ -> el is VaultItem2.Item },
                                )
                                item.copy(
                                    shapeState = shapeState,
                                    token = item.token.takeIf { keepOtp },
                                    passwords = item.passwords.takeIf { keepPassword }
                                        ?: persistentListOf(),
                                    passkeys = item.passkeys.takeIf { keepPasskey }
                                        ?: persistentListOf(),
                                    attachments2 = item.attachments2.takeIf { keepAttachment }
                                        ?: persistentListOf(),
                                )
                            }

                            else -> item
                        }
                    }
            }

            Rev(
                count = it.count,
                list = l,
                revision = (it.filterConfig?.id ?: 0) xor
                        (it.orderConfig?.hashCode() ?: 0),
            )
        }
        .flowOn(Dispatchers.Default)
        .shareIn(this, SharingStarted.WhileSubscribed(), replay = 1)

    val deeplinkCustomFilterFlow = if (args.main) {
        val customFilterKey = DeeplinkService.CUSTOM_FILTER
        deeplinkService
            .getFlow(customFilterKey)
            .filterNotNull()
            .onEach {
                deeplinkService.clear(customFilterKey)
            }
    } else {
        null
    }
    val filterListFlow = createFilterItemsFlow(
        getCipherFilters = getCipherFilters,
        outputGetter = { it.source },
        outputFlow = ciphersFilteredFlow
            .map { state ->
                state.list.mapNotNull { it as? VaultItem2.Item }
            },
        accountGetter = ::identity,
        accountFlow = getAccounts(),
        profileFlow = getProfiles(),
        cipherGetter = {
            it.source
        },
        cipherFlow = ciphersFlow,
        folderGetter = ::identity,
        folderFlow = getFolders(),
        tagGetter = ::identity,
        tagFlow = getTags(),
        collectionGetter = ::identity,
        collectionFlow = getCollections(),
        organizationGetter = ::identity,
        organizationFlow = getOrganizations(),
        input = filterResult,
        params = FilterParams(
            deeplinkCustomFilterFlow = deeplinkCustomFilterFlow,
        ),
    )
        .stateIn(this, SharingStarted.WhileSubscribed(), OurFilterResult())

    val itemsFlow = ciphersFilteredFlow
        .map {
            val list = it.list
                .toMutableList()
            if (args.canQuickFilter) {
                list.add(
                    0,
                    VaultItem2.QuickFilters(
                        id = "quick_filters",
                        items = persistentListOf(),
                    ),
                )
            }
            it.copy(list = list)
        }
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

            val a = object : WearVaultListState.Content.Items.Revision.Mutable<Pair<Int, Int>> {
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
            val b = object : WearVaultListState.Content.Items.Revision.Mutable<Int> {
                override val value: Int
                    get() = a.value.first
            }
            val c = object : WearVaultListState.Content.Items.Revision.Mutable<Int> {
                override val value: Int
                    get() = a.value.second
            }
            WearVaultListState.Content.Items(
                onSelected = { key ->
                    itemSink.value = key.orEmpty()
                },
                revision = WearVaultListState.Content.Items.Revision(
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
        getAccounts()
            .map { it.isNotEmpty() }
            .distinctUntilChanged(),
    ) { itemsContent, filters, comparatorState, hasAccounts ->
        val (comparators, sort) = comparatorState
        val revision = filters.rev xor sort.hashCode()
        val content = if (hasAccounts) {
            itemsContent
                ?: WearVaultListState.Content.Skeleton
        } else {
            WearVaultListState.Content.AddAccount(
                onAddAccount = { type ->
                    val route = registerRouteResultReceiver(
                        route = WearLoginMethodRoute(type),
                    ) {
                        // Close the login screen.
                        navigate(NavigationIntent.Pop)
                    }
                    navigate(NavigationIntent.NavigateToRoute(route))
                },
            )
        }

        WearVaultListState(
            revision = revision,
            filters = filters.items.toPersistentList(),
            sort = comparators.toPersistentList(),
            saveFilters = filters.onSave,
            clearFilters = filters.onClear,
            clearSort = if (sortDefault != sort) {
                {
                    sortSink.value = sortDefault
                }
            } else {
                null
            },
            selectCipher = cipherSink::emit,
            content = content,
            sideEffects = VaultListState.SideEffects(cipherSink),
        )
    }
}

private data class FilteredList<T>(
    val count: Int,
    val list: List<T>,
    val orderConfig: ComparatorHolder? = null,
    val filterConfig: FilterHolder? = null,
)

private fun createFilteredCiphersFlow(
    filterContext: CipherFilterContext,
    ciphersFlow: Flow<List<VaultItem2.Item>>,
    orderFlow: Flow<ComparatorHolder>,
    filterFlow: Flow<FilterHolder>,
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
                val orderComparator = Comparator<VaultItem2.Item> { aModel, bModel ->

                    var r = 0
                    if (r == 0 && orderConfig.favourites) {
                        // Place favourite items on top of the list.
                        r = -compareValues(aModel.favourite, bModel.favourite)
                    }
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
                val predicate = filterConfig.filter.prepare(filterContext, ciphers)
                filter { predicate(it.source) }
            }
        state.copy(
            list = filteredAllItems,
            filterConfig = filterConfig,
        )
    }
    .map { state ->
        val keys = mutableSetOf<String>()

        val orderConfig = state.orderConfig
        val decorator = createVaultListSortDecorator(
            orderConfig = orderConfig,
            itemCount = state.list.size,
            dateFormatter = dateFormatter,
        )

        val sectionIds = mutableSetOf<String>()
        val items = run {
            val out = mutableListOf<VaultItem2>()
            state.list.forEach { item ->
                if (!item.favourite || orderConfig?.favourites != true) {
                    val section = decorator.getOrNull(item)
                    if (section != null) {
                        // Some weird combinations of items might lead to
                        // duplicate # being used.
                        if (section.id !in sectionIds) {
                            sectionIds += section.id
                            out += section
                        } else {
                            val sections = sectionIds
                                .joinToString()

                            val msg =
                                "Duplicate sections prevented @ VaultList: $sections, [${section.id}]"
                            val exception = RuntimeException(msg)
                            recordException(exception)
                        }
                    }
                }
                out += item
            }
            out
        }.ifEmpty {
            listOf(VaultItem2.NoItems)
        }
        FilteredList(
            count = state.list.size,
            list = items,
            orderConfig = state.orderConfig,
            filterConfig = state.filterConfig,
        )
    }
