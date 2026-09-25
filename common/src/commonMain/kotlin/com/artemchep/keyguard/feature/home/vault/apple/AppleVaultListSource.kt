package com.artemchep.keyguard.feature.home.vault.apple

import androidx.compose.ui.graphics.Color
import arrow.core.identity
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.autofillTarget
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.model.CipherFilterContext
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.common.service.filter.AddCipherFilter
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.usecase.CipherToolbox
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.CopyText
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
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.GetTags
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetVaultSearchIndex
import com.artemchep.keyguard.common.usecase.GetVaultSearchQualifierCatalog
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.RenameFolderById
import com.artemchep.keyguard.common.usecase.SupervisorRead
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.duplicates.list.createCipherSelectionFlow
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.home.vault.model.SortItem
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.ComparatorHolder
import com.artemchep.keyguard.feature.home.vault.screen.FilterParams
import com.artemchep.keyguard.feature.home.vault.screen.ScrollPositionState
import com.artemchep.keyguard.feature.home.vault.screen.OurFilterResult
import com.artemchep.keyguard.feature.home.vault.screen.VaultListPersistence
import com.artemchep.keyguard.feature.home.vault.screen.createFilterItemsFlow
import com.artemchep.keyguard.feature.home.vault.screen.buildVaultBadgeTapActions
import com.artemchep.keyguard.feature.home.vault.screen.buildVaultComparator
import com.artemchep.keyguard.feature.home.vault.screen.buildVaultEffectiveCreateActions
import com.artemchep.keyguard.feature.home.vault.screen.buildVaultItemCopyActions
import com.artemchep.keyguard.feature.home.vault.screen.buildVaultItemModeMenu
import com.artemchep.keyguard.feature.home.vault.screen.buildVaultPreferredItems
import com.artemchep.keyguard.feature.home.vault.screen.createFilter
import com.artemchep.keyguard.feature.home.vault.screen.decorateVaultItems
import com.artemchep.keyguard.feature.home.vault.screen.filterVaultCiphersForMode
import com.artemchep.keyguard.feature.home.vault.screen.filterVaultItems
import com.artemchep.keyguard.feature.home.vault.screen.shouldConceal
import com.artemchep.keyguard.feature.home.vault.screen.stripPreferredPrefix
import com.artemchep.keyguard.feature.home.vault.screen.toVaultListItem
import com.artemchep.keyguard.feature.home.vault.screen.trimVaultItemBadges
import com.artemchep.keyguard.feature.home.vault.screen.createVaultSortItemsFlow
import com.artemchep.keyguard.feature.home.vault.screen.vaultListStructureRevision
import com.artemchep.keyguard.feature.home.vault.screen.vaultListToolbarFlow
import com.artemchep.keyguard.feature.home.vault.screen.vaultRenameFoldersAction
import com.artemchep.keyguard.feature.home.vault.search.engine.VAULT_SEARCH_SURFACE_VAULT_LIST
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchMatch
import com.artemchep.keyguard.feature.home.vault.search.engine.highlightTitle
import com.artemchep.keyguard.feature.home.vault.search.engine.vaultSearchQueryHandle
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.home.vault.search.query.applyVaultSearchQualifierSuggestion
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledQueryPlan
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.VaultSearchQueryHighlighter
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRouteFactory
import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.selection.selectionHandle
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.scope.Scope

/** Apple vault-list flows and id-addressed commands. */
interface AppleVaultListSource {
    /** Keyed rows + ordered entries + decorations + item count + scroll anchor + revision. */
    val state: Flow<AppleVaultListState>
    val header: Flow<AppleVaultHeader>

    /** FULL filter catalog (collapsed sections still ship their items). */
    val filterCatalog: Flow<AppleVaultFilterCatalog>
    val filterState: Flow<AppleVaultFilterState>
    val sortMenu: Flow<AppleVaultSortMenu>
    val toolbar: Flow<AppleVaultToolbar>
    val selection: Flow<AppleVaultSelection>

    /** `(rowId, token)` of the CURRENT rows, for the session's 1Hz TOTP channel. */
    val totpTokens: Flow<List<Pair<String, TotpToken>>>

    /** The canonical `cipherSink` equivalent; the session wires navigation. */
    val cipherOpenEvents: Flow<DSecret>

    // Commands are dispatched by stable id.

    fun setQuery(text: String)
    fun clearQuery()
    fun applyQualifierSuggestion()

    fun invokeFilter(id: String)
    fun clearFilters()
    fun saveFilters()
    fun toggleFilterSection(sectionId: String)

    fun invokeSort(id: String)
    fun clearSort()

    fun invokeToolbarAction(id: String)
    fun invokeSelectionAction(id: String, expectedSelectedIds: Set<String>? = null)

    fun toggleSelection(rowId: String)
    fun clearSelection()
    fun setOpenedRow(rowId: String)

    fun openVaultRow(rowId: String)
    suspend fun rowActions(rowId: String): List<AppleVaultActionDescriptor>
    fun performVaultRowAction(rowId: String, actionId: String)
    fun performVaultBadgeTap(rowId: String, badgeId: String)

    fun createItem(actionId: String)
    fun reportScroll(anchorId: String, offset: Int, structureRevision: Long)

    /** Synchronous lookup against the latest emitted state; `null` before the first emission. */
    fun rowSecret(rowId: String): DSecret?
}

internal data class AppleVaultUniverse(
    /** Post mode/trash/archive/args-filter rows, in cipher-source order. */
    val rows: List<CachedRow>,
    val rowsBySecretId: Map<String, CachedRow>,
    /** Ids rebuilt by this reconcile pass; drives the incremental sort. */
    val changedIds: Set<String>,
    /** Monotonic per-universe emission counter. */
    val emission: Long,
)

// One universe store per vault session scope (identity, like the container it replaced).
private val appleVaultListStoreLock = SynchronizedObject()
private val appleVaultListStores =
    HashMap<Scope, VaultUniverseStore<UniverseHandle<AppleVaultUniverse>>>()

private fun appleVaultListStoreFor(
    sessionKoin: Scope,
): VaultUniverseStore<UniverseHandle<AppleVaultUniverse>> = synchronized(appleVaultListStoreLock) {
    appleVaultListStores.getOrPut(sessionKoin) { VaultUniverseStore() }
}

suspend fun RememberStateFlowScope.createAppleVaultListSource(
    sessionKoin: Scope,
    args: VaultRoute.Args,
    mode: AppMode,
): AppleVaultListSource {
    val log: (String) -> Unit = { message -> println(message) }

    val deeplinkService: DeeplinkService = sessionKoin.get()
    val clearVaultSession: ClearVaultSession = sessionKoin.get()
    val equivalentDomainsBuilderFactory: com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory =
        sessionKoin.get()
    val getSuggestions: GetSuggestions<Any?> = sessionKoin.get()
    val getAccounts: GetAccounts = sessionKoin.get()
    val getProfiles: GetProfiles = sessionKoin.get()
    val getCanWrite: GetCanWrite = sessionKoin.get()
    val getCiphers: GetCiphers = sessionKoin.get()
    val getFolders: GetFolders = sessionKoin.get()
    val getTags: GetTags = sessionKoin.get()
    val getCollections: GetCollections = sessionKoin.get()
    val getOrganizations: GetOrganizations = sessionKoin.get()
    val getVaultSearchIndex: GetVaultSearchIndex = sessionKoin.get()
    val getVaultSearchQualifierCatalog: GetVaultSearchQualifierCatalog = sessionKoin.get()
    val queryHighlighter: VaultSearchQueryHighlighter = sessionKoin.get()
    val getTotpCode: GetTotpCode = sessionKoin.get()
    val getConcealFields: GetConcealFields = sessionKoin.get()
    val getAppIcons: GetAppIcons = sessionKoin.get()
    val getWebsiteIcons: GetWebsiteIcons = sessionKoin.get()
    val passkeyTargetCheck: PasskeyTargetCheck = sessionKoin.get()
    val renameFolderById: RenameFolderById = sessionKoin.get()
    val toolbox: CipherToolbox = sessionKoin.get()
    val queueSyncAll: QueueSyncAll = sessionKoin.get()
    val syncSupervisor: SupervisorRead = sessionKoin.get()
    val dateFormatter: DateFormatter = sessionKoin.get()
    val confirmationRouteFactory: ConfirmationRouteFactory = sessionKoin.get()
    val filterContext: CipherFilterContext = sessionKoin.get()
    val addCipherFilter: AddCipherFilter = sessionKoin.get()
    val getCipherFilters: GetCipherFilters = sessionKoin.get()
    val passkeysCredentialViewRouteFactory: PasskeysCredentialViewRouteFactory =
        sessionKoin.get()

    val copy = copier()
    val cipherSink = EventFlow<DSecret>()

    val storage = kotlin.run {
        val disk = loadDiskHandle(VaultListPersistence.DISK)
        PersistedStorage.InDisk(disk)
    }

    val fffFilter = args.filter ?: DFilter.All
    val fffFolderId = DFilter
        .findOne<DFilter.ById>(fffFilter) { f ->
            f.what == DFilter.ById.What.FOLDER
        }
        ?.id

    val ciphersRawFlow = filterHiddenProfiles(
        getProfiles = getProfiles,
        getCiphers = getCiphers,
        filter = args.filter,
    )

    val queryHandle = vaultSearchQueryHandle(
        key = VaultListPersistence.KEY_QUERY,
        searchBy = args.searchBy,
        getVaultSearchQualifierCatalog = getVaultSearchQualifierCatalog,
        getVaultSearchIndex = getVaultSearchIndex,
        surface = VAULT_SEARCH_SURFACE_VAULT_LIST,
        queryHighlighter = queryHighlighter,
        sharingStarted = SharingStarted.WhileSubscribed(5000L),
    )

    val itemSink = mutablePersistedFlow(VaultListPersistence.KEY_ITEM) { "" }

    val selectionHandle = selectionHandle(VaultListPersistence.KEY_SELECTION)
    // Match the Compose list: archive/trash actions remove selections that
    // leave this surface, even though the underlying cipher still exists.
    ciphersRawFlow
        .onEach { ciphers ->
            val selectedItemIds = selectionHandle.idsFlow.value
            val filteredSelectedItemIds = selectedItemIds
                .filter { itemId ->
                    val cipher = ciphers.firstOrNull { it.id == itemId }
                        ?: return@filter false
                    val passesTrashFilter = when (args.trash) {
                        true -> cipher.deletedDate != null
                        false -> cipher.deletedDate == null
                        null -> true
                    }
                    val passesArchiveFilter = when (args.archive) {
                        true -> cipher.archivedDate != null
                        false -> cipher.archivedDate == null
                        null -> true
                    }
                    passesTrashFilter && passesArchiveFilter
                }
                .toSet()
            if (filteredSelectedItemIds.size < selectedItemIds.size) {
                selectionHandle.setSelection(filteredSelectedItemIds)
            }
        }
        .launchIn(this)

    val showKeyboardSink = mutablePersistedFlow(
        key = VaultListPersistence.KEY_KEYBOARD,
        storage = if (args.canAlwaysShowKeyboard) {
            storage
        } else PersistedStorage.InMemory,
    ) { false }
    val rememberSortSink = mutablePersistedFlow(
        key = VaultListPersistence.KEY_SORT_PERSISTENT_ENABLED,
        storage = if (args.canAlwaysShowKeyboard) {
            storage
        } else PersistedStorage.InMemory,
    ) { false }
    val syncFlow = syncSupervisor
        .get(AccountTask.SYNC)
        .map { accounts ->
            accounts.isNotEmpty()
        }

    // Persist the current sort using the canonical keys.
    val sortDefault = ComparatorHolder(
        comparator = AlphabeticalSort,
        favourites = true,
    )
    val sortPersistentSink = mutablePersistedFlow(
        key = VaultListPersistence.KEY_SORT_PERSISTENT,
        storage = storage,
        serialize = ComparatorHolder::serialize,
        deserialize = ComparatorHolder::deserialize,
    ) {
        sortDefault
    }
    val sortSink = mutablePersistedFlow(
        key = VaultListPersistence.KEY_SORT,
        serialize = ComparatorHolder::serialize,
        deserialize = ComparatorHolder::deserialize,
    ) {
        if (args.sort != null) {
            ComparatorHolder(
                comparator = args.sort,
            )
        } else {
            if (rememberSortSink.value) {
                sortPersistentSink.value
            } else {
                sortDefault
            }
        }
    }
    sortSink
        .onEach { value ->
            sortPersistentSink.value = value
        }
        .launchIn(screenScope)

    val scrollPositionSink =
        mutablePersistedFlow<ScrollPositionState>(VaultListPersistence.KEY_SCROLL_STATE) { ScrollPositionState() }

    val filterResult = createFilter(addCipherFilter, confirmationRouteFactory)

    val actionsFlow = vaultListToolbarFlow(
        args = args,
        folderId = fffFolderId,
        showKeyboardSink = showKeyboardSink,
        rememberSortSink = rememberSortSink,
        syncFlow = syncFlow,
        getFolders = getFolders,
        queueSyncAll = queueSyncAll,
        clearVaultSession = clearVaultSession,
        onRename = { folders ->
            vaultRenameFoldersAction(
                folders = folders,
                confirmationRouteFactory = confirmationRouteFactory,
                renameFolderById = renameFolderById,
            )
        },
    ).shareIn(this, SharingStarted.WhileSubscribed(5000L), replay = 1)

    val configFlow = combine(
        getConcealFields(),
        getAppIcons(),
        getWebsiteIcons(),
        getCanWrite(),
    ) { concealFields, appIcons, websiteIcons, canWrite ->
        AppleVaultConfig(
            concealFields = concealFields,
            appIcons = appIcons,
            websiteIcons = websiteIcons,
            canWrite = canWrite,
        )
    }.distinctUntilChanged()
    val configSharedFlow = configFlow
        .shareIn(this, SharingStarted.WhileSubscribed(5000L), replay = 1)
    val organizationsByIdFlow = getOrganizations()
        .map { organizations ->
            organizations
                .associateBy { it.id }
        }

    // Shared universe: source set and row cache.

    val universeKey = appleUniverseKey(args, mode)
    val store = appleVaultListStoreFor(sessionKoin)
    val handle = store.acquire(universeKey) {
        createAppleVaultUniverseHandle(
            key = universeKey,
            scope = this,
            filterContext = filterContext,
            args = args,
            mode = mode,
            ciphersRawFlow = ciphersRawFlow,
            organizationsByIdFlow = organizationsByIdFlow,
            configFlow = configFlow,
            copy = copy,
            getTotpCode = getTotpCode,
            passkeyTargetCheck = passkeyTargetCheck,
            passkeysCredentialViewRouteFactory = passkeysCredentialViewRouteFactory,
            cipherSink = cipherSink,
        )
    }
    // Release the universe when this session ends.
    this.coroutineContext.job.invokeOnCompletion {
        store.release(universeKey)
    }
    val universeFlow = handle.universe

    // Preferred items, sorting, filtering, and search.

    val autofillTarget = mode.autofillTarget
    val preferredFlow = universeFlow
        .map { universe ->
            val items = universe.rows.map { it.item }
            val preferred = if (autofillTarget != null) {
                buildVaultPreferredItems(
                    items = items,
                    autofillTarget = autofillTarget,
                    getSuggestions = getSuggestions,
                    equivalentDomainsBuilderFactory = equivalentDomainsBuilderFactory,
                )
            } else {
                null
            }
            PipeItems(
                universe = universe,
                items = items,
                preferred = preferred,
            )
        }

    val sorter = IncrementalVaultSorter(log)
    val sortedFlow = preferredFlow
        .combine(
            sortSink.map { orderConfig ->
                orderConfig to buildVaultComparator(orderConfig)
            },
        ) { pipe, (orderConfig, comparator) ->
            val sorted = sorter.sort(
                universe = pipe.universe,
                items = pipe.items,
                order = orderConfig,
                comparator = comparator,
            )
            PipeSorted(
                universe = pipe.universe,
                items = sorted,
                preferred = pipe.preferred,
                orderConfig = orderConfig,
            )
        }

    val filterStage = AppleVaultFilterStage()
    val filteredFlow = sortedFlow
        .combine(filterResult.filterFlow) { pipe, filterConfig ->
            val (items, preferred) = filterStage.apply(
                filterContext = filterContext,
                items = pipe.items,
                preferred = pipe.preferred,
                holder = filterConfig,
            )
            PipeFiltered(
                universe = pipe.universe,
                items = items,
                preferred = preferred,
                orderConfig = pipe.orderConfig,
                filterConfig = filterConfig,
            )
        }

    val queriedFlow = filteredFlow
        .combine(queryHandle.searchContextFlow) { pipe, searchContext ->
            pipe to searchContext
        }
        .mapLatest { (pipe, searchContext) ->
            if (searchContext == null) {
                return@mapLatest PipeQueried(
                    universe = pipe.universe,
                    list = pipe.items,
                    preferredList = pipe.preferred,
                    orderConfig = pipe.orderConfig,
                    filterConfig = pipe.filterConfig,
                    queryConfig = null,
                    decorations = emptyMap(),
                )
            }

            val decorations = HashMap<String, AppleVaultRowDecoration>()
            val matches = searchContext.searchIndex.match(
                plan = searchContext.queryPlan,
                candidates = pipe.items,
            )
            buildAppleDecorations(matches, decorations)
            val preferredMatches = pipe.preferred
                ?.let { preferredItems ->
                    searchContext.searchIndex.match(
                        plan = searchContext.queryPlan,
                        candidates = preferredItems,
                    )
                }
            preferredMatches?.let { buildAppleDecorations(it, decorations) }
            PipeQueried(
                universe = pipe.universe,
                list = matches.map { it.item },
                preferredList = preferredMatches?.map { it.item },
                orderConfig = pipe.orderConfig,
                filterConfig = pipe.filterConfig,
                queryConfig = searchContext.queryPlan,
                decorations = decorations,
            )
        }

    // Decorate, trim, and assemble keyed state.

    val latestCtx = MutableStateFlow<AppleVaultSourceCtx?>(null)
    val passkeyTapKind = applePasskeyTapKind(mode)
    var revisionCounter = 0L

    val internalStateFlow = queriedFlow
        .map { pipe ->
            val decorated = decorateVaultItems(
                list = pipe.list,
                preferredList = pipe.preferredList,
                orderConfig = pipe.orderConfig,
                queryConfig = pipe.queryConfig,
                dateFormatter = dateFormatter,
            )
            val trimmed = trimVaultItemBadges(
                list = decorated,
                argsFilter = args.filter,
                filterConfig = pipe.filterConfig,
                orderConfig = pipe.orderConfig,
                mode = mode,
            )
            // The canonical structure-revision formula; guards the persisted
            // scroll anchor exactly like the canonical producer's Rev.
            val canonicalRev = vaultListStructureRevision(
                filterConfig = pipe.filterConfig,
                queryConfig = pipe.queryConfig,
                orderConfig = pipe.orderConfig,
            )

            val revision = ++revisionCounter
            val lastScroll = scrollPositionSink.value
            // Convert the trimmed list into keyed bridge state.
            val state = assembleAppleVaultState(
                revision = revision,
                items = trimmed,
                decorations = pipe.decorations,
                // Count cipher rows only.
                itemCount = pipe.list.size,
                includeQuickFilters = args.canQuickFilter,
                passkeyTapKind = passkeyTapKind,
                fingerprintOf = { element ->
                    val cached = pipe.universe.rowsBySecretId[element.source.id]
                    if (cached == null) {
                        log(
                            "[E]/AppleVaultList: no cached row for cipher " +
                                    "'${element.source.id}' at assembly; the universe " +
                                    "row map drifted from the pipeline!",
                        )
                    }
                    cached?.fingerprint ?: 0L
                },
                translateSection = { section ->
                    section.text?.let { translate(it) }.orEmpty()
                },
                canonicalRev = canonicalRev,
                anchorId = lastScroll.id,
                anchorOffset = lastScroll.offset,
                anchorRevision = lastScroll.revision,
            )
            // Rebuild side channels in the same order as the assembled rows.
            val outputItems = ArrayList<VaultItem2.Item>(trimmed.size)
            val totp = ArrayList<Pair<String, TotpToken>>()
            for (element in trimmed) {
                if (element is VaultItem2.Item) {
                    outputItems += element
                    val token = element.token
                    if (token != null) {
                        totp += element.id to token
                    }
                }
            }
            val ctx = AppleVaultSourceCtx(
                revision = revision,
                canonicalRev = canonicalRev,
                rowsBySecretId = pipe.universe.rowsBySecretId,
            )
            latestCtx.value = ctx
            InternalState(
                state = state,
                outputItems = outputItems,
                totp = totp,
            )
        }
        .flowOn(Dispatchers.Default)
        .shareIn(this, SharingStarted.WhileSubscribed(5000L), replay = 1)

    val stateFlow = internalStateFlow
        .map { it.state }

    val totpTokensFlow = internalStateFlow
        .map { it.totp }
        .distinctUntilChanged()

    // Filter catalog and checked state.

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
        outputFlow = internalStateFlow
            .map { it.outputItems },
        accountGetter = ::identity,
        accountFlow = getAccounts(),
        profileFlow = getProfiles(),
        cipherGetter = {
            it.source
        },
        cipherFlow = universeFlow
            .map { universe ->
                universe.rows.map { it.item }
            },
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
        includeCollapsedSectionItems = true,
    )
        .stateIn(this, SharingStarted.WhileSubscribed(), OurFilterResult())

    var lastCatalogGroups: List<AppleVaultFilterGroup>? = null
    var catalogRevision = 0L
    val filterCatalogFlow = filterListFlow
        .map { result ->
            val groups = buildAppleFilterCatalogGroups(result.items)
            if (groups != lastCatalogGroups) {
                catalogRevision += 1
                lastCatalogGroups = groups
            }
            AppleVaultFilterCatalog(
                revision = catalogRevision,
                groups = groups,
            )
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .shareIn(this, SharingStarted.WhileSubscribed(5000L), replay = 1)

    val filterStateFlow = filterListFlow
        .map { result ->
            val checkedIds = ArrayList<String>()
            val enabledIds = ArrayList<String>()
            result.items.forEach { item ->
                if (item is FilterItem.Item) {
                    if (item.checked) checkedIds += item.id
                    if (item.enabled) enabledIds += item.id
                }
            }
            AppleVaultFilterState(
                filterRevision = result.rev,
                checkedIds = checkedIds,
                enabledIds = enabledIds,
                canClear = result.onClear != null,
                canSave = result.onSave != null,
                // Count checked filter chips.
                activeCount = checkedIds.size,
            )
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    // Sort menu.

    val comparatorsListFlow = createVaultSortItemsFlow(sortSink)
        .shareIn(this, SharingStarted.WhileSubscribed(5000L), replay = 1)

    val sortMenuFlow = combine(
        comparatorsListFlow,
        sortSink,
        queryHandle.queryPairFlow,
    ) { items, sort, queryPair ->
        Triple(items, sort, queryPair.second)
    }
        .map { (items, sort, queryTrimmed) ->
            AppleVaultSortMenu(
                items = items.map { item ->
                    when (item) {
                        is SortItem.Section -> AppleVaultSortItem(
                            id = item.id,
                            title = item.text?.let { translate(it) }.orEmpty(),
                            symbol = "",
                            checked = false,
                            isSection = true,
                        )

                        is SortItem.Item -> AppleVaultSortItem(
                            id = item.id,
                            title = translate(item.title),
                            symbol = "",
                            checked = item.checked,
                            isSection = false,
                        )
                    }
                },
                canClear = sort != sortDefault,
                // Hide sorting while searching.
                visible = queryTrimmed.isEmpty(),
            )
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    // Toolbar.

    val toolbarFlow = actionsFlow
        .combine(syncFlow) { items, syncing -> items to syncing }
        .map { (items, syncing) ->
            AppleVaultToolbar(
                actions = buildAppleActionDescriptors(items, surface = "toolbar", log = log),
                syncing = syncing,
            )
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    // Selection.

    val selectionRawFlow = createCipherSelectionFlow(
        selectionHandle = selectionHandle,
        ciphersFlow = ciphersRawFlow,
        collectionsFlow = getCollections(),
        canWriteFlow = getCanWrite(),
        confirmationRouteFactory = confirmationRouteFactory,
        toolbox = toolbox,
    ).shareIn(this, SharingStarted.WhileSubscribed(5000L), replay = 1)

    val selectionFlow = selectionRawFlow
        .map { selection ->
            if (selection == null) {
                AppleVaultSelection(
                    count = 0,
                    actions = emptyList(),
                )
            } else {
                AppleVaultSelection(
                    count = selection.count,
                    selectedIds = selection.selectedIds,
                    actions = buildAppleActionDescriptors(
                        items = selection.actions,
                        surface = "selection",
                        log = log,
                    ),
                )
            }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    // Header.

    val hasAccountsFlow = getAccounts()
        .map { it.isNotEmpty() }
        .distinctUntilChanged()
    // Native list selection also drives the detail pane. Selecting an item
    // does not change entitlement or prevent creating another item.
    val paywallOkFlow = getCanWrite()
    val loadedFlow = internalStateFlow
        .map { true }
        .onStart { emit(false) }
        .distinctUntilChanged()
    val queryCursorSink = MutableStateFlow(-1)

    val queryStateFlow = combine(
        queryHandle.queryPairFlow,
        queryHandle.queryHighlightingFlow,
        queryHandle.queryQualifierSuggestionFlow,
    ) { queryPair, highlighting, suggestion ->
        HeaderQuery(
            cell = queryPair.first,
            trimmed = queryPair.second,
            highlighting = highlighting,
            suggestion = suggestion,
        )
    }

    val headerFlow = combine(
        queryStateFlow,
        hasAccountsFlow,
        paywallOkFlow,
        showKeyboardSink,
        loadedFlow,
    ) { query, hasAccounts, paywallOk, showKeyboard, loaded ->
        HeaderCtx(
            query = query,
            hasAccounts = hasAccounts,
            paywallOk = paywallOk,
            showKeyboard = showKeyboard,
            loaded = loaded,
        )
    }
        .combine(syncFlow) { ctx, syncing -> ctx to syncing }
        .combine(queryCursorSink) { (ctx, syncing), cursor -> Triple(ctx, syncing, cursor) }
        .map { (ctx, syncing, cursor) ->
            val hasAccounts = ctx.hasAccounts
            val queryCell = ctx.query.cell
            // Canonical order: gate on hasAccounts first, then apply the
            // premium paywall swap.
            val effectiveCreate = buildVaultEffectiveCreateActions(
                mode = mode,
                queryTrimmed = ctx.query.trimmed,
                hasAccounts = hasAccounts,
                paywallOk = ctx.paywallOk,
            )
            val paywalled = effectiveCreate.paywalled
            val effectiveActions = effectiveCreate.actions
            val suggestion = ctx.query.suggestion
            val suggestionQuery = suggestion
                ?.let {
                    applyVaultSearchQualifierSuggestion(
                        query = queryCell.text,
                        suggestion = it,
                    ).text
                }
                .orEmpty()
            AppleVaultHeader(
                loaded = ctx.loaded && hasAccounts,
                needsAccount = !hasAccounts,
                refreshing = syncing,
                query = if (hasAccounts) queryCell.text else "",
                queryRevision = if (hasAccounts) queryCell.revision else 0,
                queryHighlighting = if (hasAccounts) {
                    ctx.query.highlighting.spans
                        .flatMap { span ->
                            listOf(span.start, span.end, span.role.ordinal)
                        }
                } else {
                    emptyList()
                },
                queryCursor = cursor,
                qualifierSuggestion = suggestion?.text.orEmpty(),
                qualifierSuggestionQuery = suggestionQuery,
                showKeyboard = ctx.showKeyboard && hasAccounts,
                paywalled = paywalled,
                createActions = buildAppleActionDescriptors(
                    items = effectiveActions,
                    surface = "create",
                    log = log,
                ),
            )
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    // Commands.

    val scope = this

    fun launchCommand(block: suspend () -> Unit) {
        scope.launch(Dispatchers.Default) {
            block()
        }
    }

    suspend fun resolveSecret(rowId: String): DSecret? {
        val secretId = stripPreferredPrefix(rowId)
        latestCtx.value
            ?.rowsBySecretId
            ?.get(secretId)
            ?.let { return it.item.source }
        // Cold path: pull one state emission, then retry.
        internalStateFlow.first()
        return latestCtx.value
            ?.rowsBySecretId
            ?.get(secretId)
            ?.item
            ?.source
    }

    suspend fun awaitFilterResult(): OurFilterResult {
        val current = filterListFlow.value
        if (current.items.isNotEmpty()) {
            return current
        }
        return withTimeoutOrNull(ROW_ACTION_TIMEOUT_MS) {
            filterListFlow.first { it.items.isNotEmpty() }
        } ?: filterListFlow.value
    }

    suspend fun buildRowActionMenu(secret: DSecret): List<ContextItem> {
        val cfg = configSharedFlow.first()
        // `toVaultListItem` conceals when either the preference or the
        // per-item reprompt is set; live resolution mirrors it.
        val concealFields = secret.shouldConceal(cfg.concealFields)
        val copyActions = secret.buildVaultItemCopyActions(
            copy = copy,
            getTotpCode = getTotpCode,
            concealFields = concealFields,
        )
        val action = buildVaultItemModeMenu(
            mode = mode,
            secret = secret,
            copyActions = copyActions,
            canWrite = cfg.canWrite,
            cipherSink = cipherSink,
        )
        return when (action) {
            is VaultItem2.Item.Action.Dropdown -> action.actions
            // Main mode exposes copy actions for Go / None rows; other modes keep
            // the canonical no-menu behavior.
            else -> when (mode) {
                is AppMode.Main -> copyActions
                else -> emptyList()
            }
        }
    }

    return object : AppleVaultListSource {
        override val state: Flow<AppleVaultListState> = stateFlow
        override val header: Flow<AppleVaultHeader> = headerFlow
        override val filterCatalog: Flow<AppleVaultFilterCatalog> = filterCatalogFlow
        override val filterState: Flow<AppleVaultFilterState> = filterStateFlow
        override val sortMenu: Flow<AppleVaultSortMenu> = sortMenuFlow
        override val toolbar: Flow<AppleVaultToolbar> = toolbarFlow
        override val selection: Flow<AppleVaultSelection> = selectionFlow
        override val totpTokens: Flow<List<Pair<String, TotpToken>>> = totpTokensFlow
        override val cipherOpenEvents: Flow<DSecret> = cipherSink

        override fun setQuery(text: String) {
            queryCursorSink.value = -1
            queryHandle.onChange(text)
        }

        override fun clearQuery() {
            queryCursorSink.value = -1
            queryHandle.setText("")
        }

        override fun applyQualifierSuggestion() {
            launchCommand {
                val suggestion = queryHandle.queryQualifierSuggestionFlow.first()
                if (suggestion == null) {
                    log("[W]/AppleVaultList: applyQualifierSuggestion with no active suggestion; ignored.")
                    return@launchCommand
                }
                val result = applyVaultSearchQualifierSuggestion(
                    query = queryHandle.querySink.value.text,
                    suggestion = suggestion,
                )
                queryHandle.setText(result.text)
                queryCursorSink.value = result.cursor
            }
        }

        override fun invokeFilter(id: String) {
            launchCommand {
                val result = awaitFilterResult()
                val item = result.items.firstOrNull { it.id == id }
                val onClick = when (item) {
                    is FilterItem.Section -> item.onClick
                    is FilterItem.ChipItem -> item.onClick
                    is FilterItem.ListItem -> item.onClick
                    null -> null
                }
                if (onClick != null) {
                    onClick()
                } else {
                    log("[E]/AppleVaultList: invokeFilter MISS '$id' â no enabled filter item with that id!")
                }
            }
        }

        override fun clearFilters() {
            launchCommand {
                val onClear = filterListFlow.value.onClear
                if (onClear != null) {
                    onClear()
                } else {
                    log("[W]/AppleVaultList: clearFilters with no active filters; ignored.")
                }
            }
        }

        override fun saveFilters() {
            launchCommand {
                val onSave = filterListFlow.value.onSave
                if (onSave != null) {
                    onSave()
                } else {
                    log("[W]/AppleVaultList: saveFilters with no active filters; ignored.")
                }
            }
        }

        override fun toggleFilterSection(sectionId: String) {
            launchCommand {
                val result = awaitFilterResult()
                val section = result.items
                    .firstOrNull { it is FilterItem.Section && it.sectionId == sectionId }
                        as? FilterItem.Section
                val onClick = section?.onClick
                if (onClick != null) {
                    onClick()
                } else {
                    log("[E]/AppleVaultList: toggleFilterSection MISS '$sectionId'!")
                }
            }
        }

        override fun invokeSort(id: String) {
            launchCommand {
                val items = comparatorsListFlow.first()
                val target = items
                    .filterIsInstance<SortItem.Item>()
                    .firstOrNull { it.id == id }
                val onClick = target?.onClick
                if (onClick != null) {
                    onClick()
                } else {
                    log("[E]/AppleVaultList: invokeSort MISS '$id'!")
                }
            }
        }

        override fun clearSort() {
            sortSink.value = sortDefault
        }

        override fun invokeToolbarAction(id: String) {
            launchCommand {
                val items = actionsFlow.first()
                val target = items
                    .filterIsInstance<FlatItemAction>()
                    .firstOrNull { it.id == id }
                val onClick = target?.onClick
                if (onClick != null) {
                    onClick()
                } else {
                    log("[E]/AppleVaultList: invokeAction MISS '$id' @ toolbar!")
                }
            }
        }

        override fun invokeSelectionAction(id: String, expectedSelectedIds: Set<String>?) {
            launchCommand {
                val selection = selectionRawFlow.first()
                if (expectedSelectedIds != null &&
                    (selection?.selectedIds != expectedSelectedIds ||
                        selectionHandle.idsFlow.value != expectedSelectedIds)
                ) return@launchCommand
                val target = selection?.actions
                    ?.filterIsInstance<FlatItemAction>()
                    ?.firstOrNull { it.id == id }
                val onClick = target?.onClick
                if (onClick != null) {
                    onClick()
                } else {
                    log("[E]/AppleVaultList: invokeAction MISS '$id' @ selection!")
                }
            }
        }

        override fun toggleSelection(rowId: String) {
            val secretId = stripPreferredPrefix(rowId)
            selectionHandle.toggleSelection(secretId)
        }

        override fun clearSelection() {
            selectionHandle.clearSelection()
        }

        override fun setOpenedRow(rowId: String) {
            itemSink.value = rowId
        }

        override fun openVaultRow(rowId: String) {
            launchCommand {
                val secret = resolveSecret(rowId)
                if (secret == null) {
                    log("[E]/AppleVaultList: openVaultRow MISS '$rowId' â no such row!")
                    return@launchCommand
                }
                // The canonical Go semantics: remember the opened row and
                // route the cipher through the sink.
                itemSink.value = rowId
                cipherSink.emit(secret)
            }
        }

        override suspend fun rowActions(rowId: String): List<AppleVaultActionDescriptor> {
            val secret = resolveSecret(rowId)
            if (secret == null) {
                log("[E]/AppleVaultList: rowActions MISS '$rowId' â no such row!")
                return emptyList()
            }
            val menu = buildRowActionMenu(secret)
            return scope.buildAppleActionDescriptors(menu, surface = "row", log = log)
        }

        override fun performVaultRowAction(rowId: String, actionId: String) {
            launchCommand {
                val secret = resolveSecret(rowId)
                if (secret == null) {
                    log("[E]/AppleVaultList: performVaultRowAction MISS row '$rowId'!")
                    return@launchCommand
                }
                val menu = buildRowActionMenu(secret)
                val target = menu
                    .filterIsInstance<FlatItemAction>()
                    .firstOrNull { it.id == actionId }
                val onClick = target?.onClick
                if (onClick != null) {
                    onClick()
                } else {
                    log("[E]/AppleVaultList: invokeAction MISS '$actionId' @ row '$rowId'!")
                }
            }
        }

        override fun performVaultBadgeTap(rowId: String, badgeId: String) {
            launchCommand {
                val secret = resolveSecret(rowId)
                if (secret == null) {
                    log("[E]/AppleVaultList: performVaultBadgeTap MISS row '$rowId'!")
                    return@launchCommand
                }
                val taps = buildVaultBadgeTapActions(
                    mode = mode,
                    secret = secret,
                    passkeyTargetCheck = passkeyTargetCheck,
                    passkeysCredentialViewRouteFactory = passkeysCredentialViewRouteFactory,
                )
                when {
                    badgeId.startsWith("password.") -> {
                        val login = secret.login
                        if (login == null) {
                            log("[E]/AppleVaultList: badge tap '$badgeId' on a non-login row '$rowId'!")
                            return@launchCommand
                        }
                        // The canonical row nulls the password tap for
                        // reprompt-protected items.
                        val onClick = taps.onClickPassword(login)
                            ?.takeUnless { secret.reprompt }
                        onClick?.invoke()
                    }

                    badgeId.startsWith("passkey.") -> {
                        val credentialId = badgeId.removePrefix("passkey.")
                        val credential = secret.login
                            ?.fido2Credentials
                            ?.firstOrNull { it.credentialId == credentialId }
                        if (credential == null) {
                            log("[E]/AppleVaultList: badge tap MISS credential '$credentialId' @ '$rowId'!")
                            return@launchCommand
                        }
                        taps.onClickPasskey(credential)?.invoke()
                    }

                    badgeId.startsWith("attachment.") -> {
                        val attachmentId = badgeId.removePrefix("attachment.")
                        val attachment = secret.attachments
                            .firstOrNull { it.id == attachmentId }
                        if (attachment == null) {
                            log("[E]/AppleVaultList: badge tap MISS attachment '$attachmentId' @ '$rowId'!")
                            return@launchCommand
                        }
                        taps.onClickAttachment(attachment)?.invoke()
                    }

                    else -> log("[E]/AppleVaultList: unknown badge id '$badgeId' @ '$rowId'!")
                }
            }
        }

        override fun createItem(actionId: String) {
            launchCommand {
                val hasAccounts = hasAccountsFlow.first()
                val paywallOk = paywallOkFlow.first()
                val queryTrimmed = queryHandle.querySink.value.text.trim()
                val effectiveActions = buildVaultEffectiveCreateActions(
                    mode = mode,
                    queryTrimmed = queryTrimmed,
                    hasAccounts = hasAccounts,
                    paywallOk = paywallOk,
                ).actions
                val target = effectiveActions.firstOrNull { it.id == actionId }
                val onClick = target?.onClick
                if (onClick != null) {
                    onClick()
                } else {
                    log("[E]/AppleVaultList: invokeAction MISS '$actionId' @ create!")
                }
            }
        }

        override fun reportScroll(anchorId: String, offset: Int, structureRevision: Long) {
            val ctx = latestCtx.value
                ?: return
            // The identity guard: only accept scroll reports made against
            // the structure the session is currently rendering â the Apple
            // equivalent of the canonical `localScrollPositionKey` check.
            if (structureRevision != ctx.revision) {
                return
            }
            scrollPositionSink.value = ScrollPositionState(
                id = anchorId,
                offset = offset,
                revision = ctx.canonicalRev,
            )
        }

        override fun rowSecret(rowId: String): DSecret? {
            val secretId = stripPreferredPrefix(rowId)
            return latestCtx.value
                ?.rowsBySecretId
                ?.get(secretId)
                ?.item
                ?.source
        }
    }
}

// Universe

private fun appleUniverseKey(
    args: VaultRoute.Args,
    mode: AppMode,
): UniverseKey {
    val modeKind = when (mode) {
        is AppMode.Main -> AppleVaultListConfig.MODE_MAIN
        is AppMode.QuickSearch -> AppleVaultListConfig.MODE_QUICK_SEARCH
        is AppMode.Pick -> AppleVaultListConfig.MODE_PICK
        is AppMode.Save -> AppleVaultListConfig.MODE_SAVE
        is AppMode.PickPasskey -> AppleVaultListConfig.MODE_PICK_PASSKEY
        is AppMode.SavePasskey -> AppleVaultListConfig.MODE_SAVE_PASSKEY
        is AppMode.SavePassword -> AppleVaultListConfig.MODE_SAVE_PASSWORD
    }
    fun triState(value: Boolean?): Int = when (value) {
        null -> AppleVaultListConfig.TRISTATE_ANY
        false -> AppleVaultListConfig.TRISTATE_OFF
        true -> AppleVaultListConfig.TRISTATE_ON
    }
    // Include mode-carried filter inputs in the universe key.
    val filterKey = buildString {
        append(args.filter?.toString().orEmpty())
        if (mode is AppMode.PickPasskey) {
            append("|passkeyTarget:")
            append(mode.target)
        } else if (mode is AppMode.HasType) {
            append("|type:")
            append(mode.type)
        }
    }
    return UniverseKey(
        mode = modeKind,
        trash = triState(args.trash),
        archive = triState(args.archive),
        filterKey = filterKey,
        searchByPassword = args.searchBy == VaultRoute.Args.SearchBy.PASSWORD,
    )
}

private fun createAppleVaultUniverseHandle(
    key: UniverseKey,
    scope: RememberStateFlowScope,
    filterContext: CipherFilterContext,
    args: VaultRoute.Args,
    mode: AppMode,
    ciphersRawFlow: Flow<List<DSecret>>,
    organizationsByIdFlow: Flow<Map<String, DOrganization>>,
    configFlow: Flow<AppleVaultConfig>,
    copy: CopyText,
    getTotpCode: GetTotpCode,
    passkeyTargetCheck: PasskeyTargetCheck,
    passkeysCredentialViewRouteFactory: PasskeysCredentialViewRouteFactory,
    cipherSink: EventFlow<DSecret>,
): UniverseHandle<AppleVaultUniverse> {
    val bridge = SuspendRowBuilderBridge()
    val rowCache = VaultRowCache(rebuild = bridge::take)
    // Selection and opened state use separate channels.
    val frozenLocalState = MutableStateFlow(
        VaultItem2.Item.LocalState(
            openedState = VaultItem2.Item.OpenedState(false),
            selectableItemState = SelectableItemState(
                selected = false,
                selecting = false,
                can = true,
                onClick = null,
                onLongClick = null,
            ),
        ),
    )
    // Keep fingerprints unique across flow restarts.
    val configEpochs = EpochCounter<AppleVaultConfig>()
    val orgEpochs = EpochCounter<Map<String, DOrganization>>()
    var emission = 0L

    val universeFlow = combine(
        ciphersRawFlow,
        organizationsByIdFlow,
        configFlow,
    ) { secrets, organizationsById, cfg ->
        Triple(secrets, organizationsById, cfg)
    }
        .mapLatest { (secrets, organizationsById, cfg) ->
            val stateFiltered = filterVaultCiphersForMode(
                ciphers = secrets,
                mode = mode,
                args = args,
                passkeyTargetCheck = passkeyTargetCheck,
            )

            val epochs = FingerprintEpochs(
                config = configEpochs.epochOf(cfg),
                org = orgEpochs.epochOf(organizationsById),
            )
            bridge.prepare(stateFiltered, epochs) { secret ->
                val badgeTapActions = scope.buildVaultBadgeTapActions(
                    mode = mode,
                    secret = secret,
                    passkeyTargetCheck = passkeyTargetCheck,
                    passkeysCredentialViewRouteFactory = passkeysCredentialViewRouteFactory,
                )
                val item = secret.toVaultListItem(
                    copy = copy,
                    translator = scope,
                    getTotpCode = getTotpCode,
                    concealFields = cfg.concealFields,
                    appIcons = cfg.appIcons,
                    websiteIcons = cfg.websiteIcons,
                    organizationsById = organizationsById,
                    onClick = { actions ->
                        scope.buildVaultItemModeMenu(
                            mode = mode,
                            secret = secret,
                            copyActions = actions,
                            canWrite = cfg.canWrite,
                            cipherSink = cipherSink,
                        )
                    },
                    onClickAttachment = badgeTapActions.onClickAttachment,
                    onClickPasskey = badgeTapActions.onClickPasskey,
                    onClickPassword = badgeTapActions.onClickPassword,
                    localStateFlow = frozenLocalState,
                )
                CachedRow(
                    // Stamped by the cache.
                    fingerprint = 0L,
                    item = item,
                )
            }
            // Keep cache and shadow state transactional.
            val result = rowCache.reconcile(stateFiltered, epochs)
            bridge.commit()

            // Apply the base filter to each item.
            val rows = if (args.filter != null) {
                val ciphers = result.rows.map { it.item.source }
                val predicate = args.filter.prepare(filterContext, ciphers)
                result.rows.filter { predicate(it.item.source) }
            } else {
                result.rows
            }
            AppleVaultUniverse(
                rows = rows,
                rowsBySecretId = rows.associateBy { it.item.source.id },
                changedIds = result.changedIds,
                emission = emission++,
            )
        }
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(5000L), replay = 1)

    return UniverseHandle(
        key = key,
        universe = universeFlow,
        rowCache = rowCache,
    )
}

private class SuspendRowBuilderBridge {
    private var shadow: Map<String, Long> = emptyMap()
    private var pendingShadow: Map<String, Long>? = null
    private val prepared = HashMap<String, CachedRow>()

    suspend fun prepare(
        secrets: List<DSecret>,
        epochs: FingerprintEpochs,
        build: suspend (DSecret) -> CachedRow,
    ) {
        prepared.clear()
        pendingShadow = null
        val next = HashMap<String, Long>(secrets.size * 2)
        for (secret in secrets) {
            val fingerprint = fingerprintOf(secret, epochs)
            next[secret.id] = fingerprint
            if (shadow[secret.id] != fingerprint) {
                prepared[secret.id] = build(secret)
            }
        }
        pendingShadow = next
    }

    fun take(secret: DSecret): CachedRow = prepared[secret.id]
        ?: error(
            "AppleVaultList: the row cache asked to rebuild '${secret.id}' but the " +
                    "suspend bridge did not prepare it; the bridge's shadow " +
                    "fingerprints drifted from VaultRowCache.reconcile!",
        )

    fun commit() {
        shadow = pendingShadow ?: shadow
        pendingShadow = null
        prepared.clear()
    }
}

/** A content-derived epoch counter; bumps on every value change. */
private class EpochCounter<T : Any> {
    private var last: T? = null
    private var epoch = 0L

    fun epochOf(value: T): Long {
        if (value != last) {
            epoch += 1
            last = value
        }
        return epoch
    }
}

// Pipeline stages

private data class AppleVaultConfig(
    val concealFields: Boolean,
    val appIcons: Boolean,
    val websiteIcons: Boolean,
    val canWrite: Boolean,
)

private data class PipeItems(
    val universe: AppleVaultUniverse,
    val items: List<VaultItem2.Item>,
    val preferred: List<VaultItem2.Item>?,
)

private data class PipeSorted(
    val universe: AppleVaultUniverse,
    val items: List<VaultItem2.Item>,
    val preferred: List<VaultItem2.Item>?,
    val orderConfig: ComparatorHolder,
)

private data class PipeFiltered(
    val universe: AppleVaultUniverse,
    val items: List<VaultItem2.Item>,
    val preferred: List<VaultItem2.Item>?,
    val orderConfig: ComparatorHolder,
    val filterConfig: FilterHolder,
)

private data class PipeQueried(
    val universe: AppleVaultUniverse,
    val list: List<VaultItem2.Item>,
    val preferredList: List<VaultItem2.Item>?,
    val orderConfig: ComparatorHolder,
    val filterConfig: FilterHolder,
    val queryConfig: CompiledQueryPlan?,
    val decorations: Map<String, AppleVaultRowDecoration>,
)

private class InternalState(
    val state: AppleVaultListState,
    val outputItems: List<VaultItem2.Item>,
    val totp: List<Pair<String, TotpToken>>,
)

private class AppleVaultSourceCtx(
    val revision: Long,
    val canonicalRev: Int,
    val rowsBySecretId: Map<String, CachedRow>,
)

private data class HeaderQuery(
    val cell: com.artemchep.keyguard.feature.auth.common.TextCell,
    val trimmed: String,
    val highlighting: com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlighting,
    val suggestion: com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierSuggestion?,
)

private data class HeaderCtx(
    val query: HeaderQuery,
    val hasAccounts: Boolean,
    val paywallOk: Boolean,
    val showKeyboard: Boolean,
    val loaded: Boolean,
)

/** The incremental-sort threshold: above it a full sort is cheaper / safer. */
private const val INCREMENTAL_SORT_MAX_CHANGES = 16

private class IncrementalVaultSorter(
    private val log: (String) -> Unit,
) {
    private var lastOrder: ComparatorHolder? = null
    private var lastEmission = Long.MIN_VALUE
    private var lastSorted: List<VaultItem2.Item> = emptyList()
    private var lastIds: Set<String> = emptySet()

    fun sort(
        universe: AppleVaultUniverse,
        items: List<VaultItem2.Item>,
        order: ComparatorHolder,
        comparator: Comparator<VaultItem2.Item>,
    ): List<VaultItem2.Item> {
        val ids = items.mapTo(HashSet(items.size * 2)) { it.id }
        val incremental = lastOrder == order &&
                universe.emission == lastEmission + 1 &&
                universe.changedIds.size <= INCREMENTAL_SORT_MAX_CHANGES &&
                lastSorted.isNotEmpty()
        var sorted: List<VaultItem2.Item>
        if (incremental) {
            // Remove every changed row and every row gone from the input,
            // then binary-reinsert the (few) changed rows that survived.
            val staleIds = HashSet(universe.changedIds)
            for (id in lastIds) {
                if (id !in ids) {
                    staleIds += id
                }
            }
            val base = ArrayList<VaultItem2.Item>(items.size)
            for (item in lastSorted) {
                if (item.id !in staleIds) {
                    base += item
                }
            }
            for (item in items) {
                if (item.id in universe.changedIds) {
                    val index = base.binarySearch(item, comparator)
                    val insertion = if (index >= 0) index else -index - 1
                    base.add(insertion, item)
                }
            }
            sorted = base
            val sizeOk = sorted.size == items.size
            val sortedOk = isRelease || sorted.isSortedWith(comparator)
            if (!sizeOk || !sortedOk) {
                log(
                    "[E]/AppleVaultList: incremental sort inconsistency " +
                            "(sizeOk=$sizeOk sortedOk=$sortedOk in=${items.size} " +
                            "out=${sorted.size} changed=${universe.changedIds.size}); " +
                            "falling back to a full sort!",
                )
                sorted = items.sortedWith(comparator)
            }
        } else {
            sorted = items.sortedWith(comparator)
        }
        lastOrder = order
        lastEmission = universe.emission
        lastSorted = sorted
        lastIds = ids
        return sorted
    }
}

private fun List<VaultItem2.Item>.isSortedWith(
    comparator: Comparator<VaultItem2.Item>,
): Boolean {
    for (i in 1 until size) {
        if (comparator.compare(this[i - 1], this[i]) > 0) {
            return false
        }
    }
    return true
}

private class AppleVaultFilterStage {
    private var lastItems: List<VaultItem2.Item>? = null
    private var lastPreferred: List<VaultItem2.Item>? = null
    private var lastHolder: FilterHolder? = null
    private var lastResult: Pair<List<VaultItem2.Item>, List<VaultItem2.Item>?>? = null

    suspend fun apply(
        filterContext: CipherFilterContext,
        items: List<VaultItem2.Item>,
        preferred: List<VaultItem2.Item>?,
        holder: FilterHolder,
    ): Pair<List<VaultItem2.Item>, List<VaultItem2.Item>?> {
        // Fast path when no filters are selected.
        if (holder.state.isEmpty()) {
            return items to preferred
        }
        val memo = lastResult
        val inputsUnchanged = lastItems === items && lastPreferred === preferred && lastHolder == holder
        if (memo != null && inputsUnchanged) {
            return memo
        }
        val filtered = filterVaultItems(
            filterContext = filterContext,
            items = items,
            filter = holder.filter,
        )
        val filteredPreferred = preferred
            ?.let { preferredItems ->
                filterVaultItems(
                    filterContext = filterContext,
                    items = preferredItems,
                    filter = holder.filter,
                )
            }
        val result = filtered to filteredPreferred
        lastItems = items
        lastPreferred = preferred
        lastHolder = holder
        lastResult = result
        return result
    }
}

private fun buildAppleDecorations(
    matches: List<VaultSearchMatch>,
    into: HashMap<String, AppleVaultRowDecoration>,
) {
    for (match in matches) {
        val terms = match.titleTerms
        val context = match.context
        if (terms.isEmpty() && context == null) {
            continue
        }
        val ranges = if (terms.isEmpty()) {
            emptyList()
        } else {
            highlightTitle(
                text = match.item.title.text,
                terms = terms,
                highlightBackgroundColor = Color.Transparent,
                highlightContentColor = Color.Transparent,
            )
                .spanStyles
                .flatMap { span -> listOf(span.start, span.end) }
        }
        into[match.item.id] = AppleVaultRowDecoration(
            id = match.item.id,
            titleRanges = ranges,
            contextBadgeText = context?.snippet.orEmpty(),
            contextBadgeSymbol = context?.field?.let(::appleVaultTextFieldSymbol).orEmpty(),
        )
    }
}

// Filter catalog projection

private fun buildAppleFilterCatalogGroups(
    items: List<FilterItem>,
): List<AppleVaultFilterGroup> {
    val groups = ArrayList<AppleVaultFilterGroup>()
    var section: FilterItem.Section? = null
    var chips = ArrayList<AppleVaultFilterChip>()

    fun flush() {
        val currentSection = section
        if (currentSection == null && chips.isEmpty()) {
            return
        }
        groups += AppleVaultFilterGroup(
            sectionId = currentSection?.sectionId
                ?: chips.firstOrNull()?.sectionId.orEmpty(),
            title = currentSection?.text.orEmpty(),
            collapsed = currentSection?.expanded == false,
            treeLayout = currentSection?.layout == FilterItemModel.Section.Layout.List,
            items = chips,
        )
    }

    for (item in items) {
        when (item) {
            is FilterItem.Section -> {
                flush()
                section = item
                chips = ArrayList()
            }

            is FilterItem.ChipItem -> chips += item.toAppleFilterChip()
            is FilterItem.ListItem -> chips += item.toAppleFilterChip()
        }
    }
    flush()
    return groups
}

private fun FilterItem.ChipItem.toAppleFilterChip(): AppleVaultFilterChip = AppleVaultFilterChip(
    id = id,
    sectionId = sectionId,
    title = title,
    text = text.orEmpty(),
    // Project chip accents by stable id.
    symbol = "",
    tintLightArgb = 0,
    tintDarkArgb = 0,
    depth = 0,
    nodeId = "",
    parentNodeId = "",
    expandable = false,
    selectable = true,
    isApply = filter is FilterItem.Item.Filter.Apply,
)

private fun FilterItem.ListItem.toAppleFilterChip(): AppleVaultFilterChip = AppleVaultFilterChip(
    id = id,
    sectionId = sectionId,
    title = title,
    text = text.orEmpty(),
    symbol = "",
    tintLightArgb = 0,
    tintDarkArgb = 0,
    depth = depth,
    nodeId = nodeId,
    parentNodeId = parentNodeId.orEmpty(),
    expandable = expandable,
    selectable = onClick != null,
    isApply = filter is FilterItem.Item.Filter.Apply,
)

private const val ROW_ACTION_TIMEOUT_MS = 2_000L
