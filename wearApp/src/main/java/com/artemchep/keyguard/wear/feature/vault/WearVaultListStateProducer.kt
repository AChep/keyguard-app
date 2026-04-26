package com.artemchep.keyguard.wear.feature.vault

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import arrow.core.identity
import arrow.core.partially1
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.nullable
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.formatH
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
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
import com.artemchep.keyguard.feature.decorator.ItemDecorator
import com.artemchep.keyguard.feature.decorator.ItemDecoratorDate
import com.artemchep.keyguard.feature.decorator.ItemDecoratorNone
import com.artemchep.keyguard.feature.decorator.ItemDecoratorTitle
import com.artemchep.keyguard.feature.generator.history.mapLatestScoped
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.component.obscurePassword
import com.artemchep.keyguard.feature.home.vault.model.SortItem
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.ComparatorHolder
import com.artemchep.keyguard.feature.home.vault.screen.OhOhOh
import com.artemchep.keyguard.feature.home.vault.screen.FilterParams
import com.artemchep.keyguard.feature.home.vault.screen.OurFilterResult
import com.artemchep.keyguard.feature.home.vault.screen.VaultListState
import com.artemchep.keyguard.feature.home.vault.screen.ah
import com.artemchep.keyguard.feature.home.vault.screen.createFilter
import com.artemchep.keyguard.feature.home.vault.screen.toVaultListItem
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.home.vault.search.sort.LastCreatedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.LastModifiedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordLastModifiedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordStrengthSort
import com.artemchep.keyguard.feature.home.vault.util.AlphabeticalSortMinItemsSize
import com.artemchep.keyguard.feature.largetype.LargeTypeRoute
import com.artemchep.keyguard.feature.largetype.LargeTypeRoute.Args
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRoute
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRouteFactory
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.wear.feature.auth.WearLoginMethodRoute
import org.jetbrains.compose.resources.StringResource
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
import org.kodein.di.DirectDI
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
internal fun wearVaultListScreenState(
    args: VaultRoute.Args,
): WearVaultListState = with(localDI().direct) {
    wearVaultListScreenState(
        directDI = this,
        args = args,
        deeplinkService = instance(),
        getAccounts = instance(),
        getProfiles = instance(),
        getCanWrite = instance(),
        getCiphers = instance(),
        getFolders = instance(),
        getTags = instance(),
        getCollections = instance(),
        getOrganizations = instance(),
        getTotpCode = instance(),
        getConcealFields = instance(),
        getAppIcons = instance(),
        getWebsiteIcons = instance(),
        passkeyTargetCheck = instance(),
        syncSupervisor = instance(),
        dateFormatter = instance(),
        clipboardService = instance(),
        passkeysCredentialViewRouteFactory = instance(),
    )
}

@Composable
internal fun wearVaultListScreenState(
    directDI: DirectDI,
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

    val copy = copy(clipboardService)

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
    val scrollPositionSink = mutablePersistedFlow<OhOhOh>("scroll_state") { OhOhOh() }

    val filterResult = createFilter(directDI)

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
    ) = SortItem.Item(
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
        val item: SortItem.Item,
        val subItems: List<SortItem.Item>,
    )

    val cam = mapOf(
        AlphabeticalSort to Fuu(
            item = createComparatorAction(
                id = "title",
                icon = Icons.Outlined.SortByAlpha,
                title = Res.string.sortby_title_title,
                config = ComparatorHolder(
                    comparator = AlphabeticalSort,
                    favourites = true,
                ),
            ),
            subItems = listOf(
                createComparatorAction(
                    id = "title_normal",
                    title = Res.string.sortby_title_normal_mode,
                    config = ComparatorHolder(
                        comparator = AlphabeticalSort,
                        favourites = true,
                    ),
                ),
                createComparatorAction(
                    id = "title_rev",
                    title = Res.string.sortby_title_reverse_mode,
                    config = ComparatorHolder(
                        comparator = AlphabeticalSort,
                        reversed = true,
                        favourites = true,
                    ),
                ),
            ),
        ),
        LastModifiedSort to Fuu(
            item = createComparatorAction(
                id = "modify_date",
                icon = Icons.Outlined.CalendarMonth,
                title = Res.string.sortby_modification_date_title,
                config = ComparatorHolder(
                    comparator = LastModifiedSort,
                ),
            ),
            subItems = listOf(
                createComparatorAction(
                    id = "modify_date_normal",
                    title = Res.string.sortby_modification_date_normal_mode,
                    config = ComparatorHolder(
                        comparator = LastModifiedSort,
                    ),
                ),
                createComparatorAction(
                    id = "modify_date_rev",
                    title = Res.string.sortby_modification_date_reverse_mode,
                    config = ComparatorHolder(
                        comparator = LastModifiedSort,
                        reversed = true,
                    ),
                ),
            ),
        ),
        PasswordSort to Fuu(
            item = createComparatorAction(
                id = "password",
                icon = Icons.Outlined.Password,
                title = Res.string.sortby_password_title,
                config = ComparatorHolder(
                    comparator = PasswordSort,
                ),
            ),
            subItems = listOf(
                createComparatorAction(
                    id = "password_normal",
                    title = Res.string.sortby_password_normal_mode,
                    config = ComparatorHolder(
                        comparator = PasswordSort,
                    ),
                ),
                createComparatorAction(
                    id = "password_rev",
                    title = Res.string.sortby_password_reverse_mode,
                    config = ComparatorHolder(
                        comparator = PasswordSort,
                        reversed = true,
                    ),
                ),
            ),
        ),
        PasswordLastModifiedSort to Fuu(
            item = createComparatorAction(
                id = "password_last_modified_strength",
                icon = Icons.Outlined.CalendarMonth,
                title = Res.string.sortby_password_modification_date_title,
                config = ComparatorHolder(
                    comparator = PasswordLastModifiedSort,
                ),
            ),
            subItems = listOf(
                createComparatorAction(
                    id = "password_last_modified_normal",
                    title = Res.string.sortby_password_modification_date_normal_mode,
                    config = ComparatorHolder(
                        comparator = PasswordLastModifiedSort,
                    ),
                ),
                createComparatorAction(
                    id = "password_last_modified_rev",
                    title = Res.string.sortby_password_modification_date_reverse_mode,
                    config = ComparatorHolder(
                        comparator = PasswordLastModifiedSort,
                        reversed = true,
                    ),
                ),
            ),
        ),
        PasswordStrengthSort to Fuu(
            item = createComparatorAction(
                id = "password_strength",
                icon = Icons.Outlined.Security,
                title = Res.string.sortby_password_strength_title,
                config = ComparatorHolder(
                    comparator = PasswordStrengthSort,
                ),
            ),
            subItems = listOf(
                createComparatorAction(
                    id = "password_strength_normal",
                    title = Res.string.sortby_password_strength_normal_mode,
                    config = ComparatorHolder(
                        comparator = PasswordStrengthSort,
                    ),
                ),
                createComparatorAction(
                    id = "password_strength_rev",
                    title = Res.string.sortby_password_strength_reverse_mode,
                    config = ComparatorHolder(
                        comparator = PasswordStrengthSort,
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

            val out = mutableListOf<SortItem>()
            out += mainItems
            if (subItems.isNotEmpty()) {
                out += SortItem.Section(
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

    val ciphersFilteredStateFlow = hahah(
        directDI = directDI,
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
    val filterListFlow = ah(
        directDI = directDI,
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

private data class FilteredBoo<T>(
    val count: Int,
    val list: List<T>,
    val orderConfig: ComparatorHolder? = null,
    val filterConfig: FilterHolder? = null,
)

private fun hahah(
    directDI: DirectDI,
    ciphersFlow: Flow<List<VaultItem2.Item>>,
    orderFlow: Flow<ComparatorHolder>,
    filterFlow: Flow<FilterHolder>,
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
                val predicate = filterConfig.filter.prepare(directDI, ciphers)
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
        val decorator = when {
            orderConfig?.comparator is AlphabeticalSort &&
                    // it looks ugly on small lists
                    state.list.size >= AlphabeticalSortMinItemsSize ->
                ItemDecoratorTitle<VaultItem2, VaultItem2.Item>(
                    selector = { it.title.text },
                    factory = { id, text ->
                        VaultItem2.Section(
                            id = id,
                            text = TextHolder.Value(text),
                        )
                    },
                )

            orderConfig?.comparator is LastCreatedSort ->
                ItemDecoratorDate<VaultItem2, VaultItem2.Item>(
                    dateFormatter = dateFormatter,
                    selector = { it.createdDate },
                    factory = { id, text ->
                        VaultItem2.Section(
                            id = id,
                            text = TextHolder.Value(text),
                        )
                    },
                )

            orderConfig?.comparator is LastModifiedSort ->
                ItemDecoratorDate<VaultItem2, VaultItem2.Item>(
                    dateFormatter = dateFormatter,
                    selector = { it.revisionDate },
                    factory = { id, text ->
                        VaultItem2.Section(
                            id = id,
                            text = TextHolder.Value(text),
                        )
                    },
                )

            orderConfig?.comparator is PasswordSort -> PasswordDecorator()

            orderConfig?.comparator is PasswordLastModifiedSort ->
                ItemDecoratorDate<VaultItem2, VaultItem2.Item>(
                    dateFormatter = dateFormatter,
                    selector = { it.passwordRevisionDate },
                    factory = { id, text ->
                        VaultItem2.Section(
                            id = id,
                            text = TextHolder.Value(text),
                        )
                    },
                )

            orderConfig?.comparator is PasswordStrengthSort -> PasswordStrengthDecorator()
            else -> ItemDecoratorNone
        }

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
        FilteredBoo(
            count = state.list.size,
            list = items,
            orderConfig = state.orderConfig,
            filterConfig = state.filterConfig,
        )
    }

private typealias Decorator = ItemDecorator<VaultItem2, VaultItem2.Item>

private class PasswordDecorator : Decorator {
    /**
     * Last shown password, used to not repeat the sections
     * if it stays the same.
     */
    private var lastPassword: Any? = Any()

    override suspend fun getOrNull(item: VaultItem2.Item): VaultItem2? {
        val pw = item.password
        if (pw == lastPassword) {
            return null
        }

        lastPassword = pw
        if (pw == null) {
            return VaultItem2.Section(
                id = "decorator.pw.empty",
                text = Res.string.no_password.wrap(),
            )
        }
        val text = obscurePassword(pw)
        return VaultItem2.Section(
            id = "decorator.pw.$pw",
            text = TextHolder.Value(text),
            caps = false,
        )
    }
}

private class PasswordStrengthDecorator : Decorator {
    /**
     * Last shown password score, used to not repeat the sections
     * if it stays the same.
     */
    private var lastScore: Any? = Any()

    override suspend fun getOrNull(item: VaultItem2.Item): VaultItem2? {
        val score = item.score?.score
        if (score == lastScore) {
            return null
        }

        lastScore = score
        if (score == null) {
            return VaultItem2.Section(
                id = "decorator.pw_strength.empty",
                text = Res.string.no_password.wrap(),
            )
        }
        return VaultItem2.Section(
            id = "decorator.pw_strength.${score.name}",
            text = TextHolder.Res(score.formatH()),
        )
    }
}
