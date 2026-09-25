package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Save
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import arrow.core.identity
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.autofillTarget
import com.artemchep.keyguard.common.io.nullable
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.CipherFilterContext
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.common.service.filter.AddCipherFilter
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.usecase.CipherToolbox
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCipherOpenedHistory
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
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
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRouteFactory
import com.artemchep.keyguard.feature.auth.common.TextCell
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.keepass.KeePassLoginRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.duplicates.list.createCipherSelectionFlow
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.LeAddRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.search.engine.VAULT_SEARCH_SURFACE_VAULT_LIST
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchContext
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchTraceSink
import com.artemchep.keyguard.feature.home.vault.search.engine.formatSortForTrace
import com.artemchep.keyguard.feature.home.vault.search.engine.vaultSearchQueryHandle
import com.artemchep.keyguard.feature.home.vault.search.engine.vaultSearchTraceFlow
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierSuggestion
import com.artemchep.keyguard.feature.home.vault.search.query.applyVaultSearchQualifierSuggestion
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledQueryPlan
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlighting
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.VaultSearchQueryHighlighter
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.home.vault.search.sort.Sort
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut
import com.artemchep.keyguard.feature.navigation.keyboard.interceptKeyEvents
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRouteFactory
import com.artemchep.keyguard.leof
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.selection.selectionHandle
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.compose.currentKoinScope

@LeParcelize
data class ScrollPositionState(
    val id: String? = null,
    val offset: Int = 0,
    val revision: Int = 0,
) : LeParcelable

@LeParcelize
@Serializable
data class ComparatorHolder(
    val comparator: Sort,
    val reversed: Boolean = false,
    val favourites: Boolean = false,
) : LeParcelable {
    companion object {
        // Accepts Map<*, *> so that a state persisted by an older version, which stored the
        // flags as real Booleans, still restores.
        fun of(map: Map<*, *>): ComparatorHolder {
            return ComparatorHolder(
                comparator = Sort.valueOf(map["comparator"].toString()) ?: AlphabeticalSort,
                reversed = map["reversed"].toString() == "true",
                favourites = map["favourites"].toString() == "true",
            )
        }

        fun deserialize(
            json: Json,
            value: Map<String, String>,
        ): ComparatorHolder = of(value)

        fun serialize(
            json: Json,
            value: ComparatorHolder,
        ): Map<String, String> = value.toMap()
    }

    // Homogeneous Map<String, String>: a Map<String, Any?> is neither bundle-safe nor
    // JSON-safe, because Any? tells the persistence layer nothing about the values.
    fun toMap(): Map<String, String> = mapOf(
        "comparator" to comparator.id,
        "reversed" to reversed.toString(),
        "favourites" to favourites.toString(),
    )
}

@Composable
internal fun vaultListScreenState(
    args: VaultRoute.Args,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
    mode: AppMode,
): VaultListState = with(currentKoinScope()) {
    vaultListScreenState(
        filterContext = get(),
        addCipherFilter = get(),
        confirmationRouteFactory = get(),
        getCipherFilters = get(),
        args = args,
        highlightBackgroundColor = highlightBackgroundColor,
        highlightContentColor = highlightContentColor,
        mode = mode,
        deeplinkService = get(),
        clearVaultSession = get(),
        equivalentDomainsBuilderFactory = get(),
        getSuggestions = get(),
        getAccounts = get(),
        getProfiles = get(),
        getCanWrite = get(),
        getCiphers = get(),
        getFolders = get(),
        getTags = get(),
        getCollections = get(),
        getOrganizations = get(),
        getVaultSearchIndex = get(),
        getVaultSearchQualifierCatalog = get(),
        searchTraceSink = get(),
        queryHighlighter = get(),
        getTotpCode = get(),
        getConcealFields = get(),
        getAppIcons = get(),
        getWebsiteIcons = get(),
        getPasswordStrength = get(),
        getCipherOpenedHistory = get(),
        passkeyTargetCheck = get(),
        renameFolderById = get(),
        toolbox = get(),
        queueSyncAll = get(),
        syncSupervisor = get(),
        dateFormatter = get(),
        clipboardService = get(),
        bitwardenLoginRouteFactory = get(),
        passkeysCredentialViewRouteFactory = get(),
    )
}

@Composable
internal fun vaultListScreenState(
    filterContext: CipherFilterContext,
    addCipherFilter: AddCipherFilter,
    confirmationRouteFactory: ConfirmationRouteFactory,
    getCipherFilters: GetCipherFilters,
    args: VaultRoute.Args,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
    mode: AppMode,
    deeplinkService: DeeplinkService,
    equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
    getSuggestions: GetSuggestions<Any?>,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getCanWrite: GetCanWrite,
    getCiphers: GetCiphers,
    getFolders: GetFolders,
    getTags: GetTags,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
    getVaultSearchIndex: GetVaultSearchIndex,
    getVaultSearchQualifierCatalog: GetVaultSearchQualifierCatalog,
    searchTraceSink: VaultSearchTraceSink,
    queryHighlighter: VaultSearchQueryHighlighter,
    getTotpCode: GetTotpCode,
    getConcealFields: GetConcealFields,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
    getPasswordStrength: GetPasswordStrength,
    getCipherOpenedHistory: GetCipherOpenedHistory,
    passkeyTargetCheck: PasskeyTargetCheck,
    renameFolderById: RenameFolderById,
    clearVaultSession: ClearVaultSession,
    toolbox: CipherToolbox,
    queueSyncAll: QueueSyncAll,
    syncSupervisor: SupervisorRead,
    dateFormatter: DateFormatter,
    clipboardService: ClipboardService,
    bitwardenLoginRouteFactory: BitwardenLoginRouteFactory,
    passkeysCredentialViewRouteFactory: PasskeysCredentialViewRouteFactory,
): VaultListState = produceScreenState(
    key = VaultListPersistence.SCREEN_COMPOSE,
    initial = VaultListState(),
    args = arrayOf(
        getAccounts,
        getCiphers,
        getTotpCode,
        dateFormatter,
        clipboardService,
    ),
) {
    vaultListScreenStateProducer(
        filterContext = filterContext,
        addCipherFilter = addCipherFilter,
        confirmationRouteFactory = confirmationRouteFactory,
        getCipherFilters = getCipherFilters,
        args = args,
        highlightBackgroundColor = highlightBackgroundColor,
        highlightContentColor = highlightContentColor,
        mode = mode,
        deeplinkService = deeplinkService,
        equivalentDomainsBuilderFactory = equivalentDomainsBuilderFactory,
        getSuggestions = getSuggestions,
        getAccounts = getAccounts,
        getProfiles = getProfiles,
        getCanWrite = getCanWrite,
        getCiphers = getCiphers,
        getFolders = getFolders,
        getTags = getTags,
        getCollections = getCollections,
        getOrganizations = getOrganizations,
        getVaultSearchIndex = getVaultSearchIndex,
        getVaultSearchQualifierCatalog = getVaultSearchQualifierCatalog,
        searchTraceSink = searchTraceSink,
        queryHighlighter = queryHighlighter,
        getTotpCode = getTotpCode,
        getConcealFields = getConcealFields,
        getAppIcons = getAppIcons,
        getWebsiteIcons = getWebsiteIcons,
        getPasswordStrength = getPasswordStrength,
        getCipherOpenedHistory = getCipherOpenedHistory,
        passkeyTargetCheck = passkeyTargetCheck,
        renameFolderById = renameFolderById,
        clearVaultSession = clearVaultSession,
        toolbox = toolbox,
        queueSyncAll = queueSyncAll,
        syncSupervisor = syncSupervisor,
        dateFormatter = dateFormatter,
        clipboardService = clipboardService,
        bitwardenLoginRouteFactory = bitwardenLoginRouteFactory,
        passkeysCredentialViewRouteFactory = passkeysCredentialViewRouteFactory,
    )
}

internal suspend fun RememberStateFlowScope.vaultListScreenStateProducer(
    filterContext: CipherFilterContext,
    addCipherFilter: AddCipherFilter,
    confirmationRouteFactory: ConfirmationRouteFactory,
    getCipherFilters: GetCipherFilters,
    args: VaultRoute.Args,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
    mode: AppMode,
    deeplinkService: DeeplinkService,
    equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
    getSuggestions: GetSuggestions<Any?>,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getCanWrite: GetCanWrite,
    getCiphers: GetCiphers,
    getFolders: GetFolders,
    getTags: GetTags,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
    getVaultSearchIndex: GetVaultSearchIndex,
    getVaultSearchQualifierCatalog: GetVaultSearchQualifierCatalog,
    searchTraceSink: VaultSearchTraceSink,
    queryHighlighter: VaultSearchQueryHighlighter,
    getTotpCode: GetTotpCode,
    getConcealFields: GetConcealFields,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
    getPasswordStrength: GetPasswordStrength,
    getCipherOpenedHistory: GetCipherOpenedHistory,
    passkeyTargetCheck: PasskeyTargetCheck,
    renameFolderById: RenameFolderById,
    clearVaultSession: ClearVaultSession,
    toolbox: CipherToolbox,
    queueSyncAll: QueueSyncAll,
    syncSupervisor: SupervisorRead,
    dateFormatter: DateFormatter,
    clipboardService: ClipboardService,
    bitwardenLoginRouteFactory: BitwardenLoginRouteFactory,
    passkeysCredentialViewRouteFactory: PasskeysCredentialViewRouteFactory,
): Flow<VaultListState> {
    // Start all repository-backed session sources before the disk restore. The
    // hub invokes each use case once and owns replay only for this screen/session
    // scope, so the persisted-state read can overlap repository readiness.
    val sessionInputs = VaultSessionInputs(
        scope = this,
        getCiphers = getCiphers,
        getProfiles = getProfiles,
        getOrganizations = getOrganizations,
        getCollections = getCollections,
        getAccounts = getAccounts,
        getCanWrite = getCanWrite,
        getConcealFields = getConcealFields,
        getAppIcons = getAppIcons,
        getWebsiteIcons = getWebsiteIcons,
    )
    val writeCapabilityFlow = vaultListWriteCapabilityFlow(
        hasAccountsFlow = sessionInputs.accounts
            .map { accounts ->
                accounts.isNotEmpty()
            }
            .distinctUntilChanged(),
        capabilityFlow = sessionInputs.canWrite,
    )
        .stateIn(
            scope = this,
            started = SharingStarted.Eagerly,
            initialValue = WriteCapability.Unknown,
        )
    val canWriteFlow = writeCapabilityFlow
        .map { capability ->
            capability == WriteCapability.Allowed
        }
        .distinctUntilChanged()
    // These deferred metadata/action sources
    // are also invoked once locally.
    val foldersFlow = getFolders()
        .shareIn(
            scope = this,
            started = SharingStarted.WhileSubscribed(5_000L),
            replay = 1,
        )
    val tagsFlow = getTags()
        .shareIn(
            scope = this,
            started = SharingStarted.WhileSubscribed(5_000L),
            replay = 1,
        )
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

    val copy = copier()

    val ciphersRawFlow = filterHiddenProfiles(
        profilesFlow = sessionInputs.profiles,
        ciphersFlow = sessionInputs.ciphers,
        filter = args.filter,
    )
        .shareIn(
            scope = this,
            started = SharingStarted.WhileSubscribed(5000L),
            replay = 1,
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

    fun clearField() {
        queryHandle.setText("")
    }

    fun focusField() {
        queryHandle.queryFocusSink.emit(Unit)
    }

    // Intercept the back button while the
    // search query is not empty.
    interceptBackPress(
        interceptorFlow = queryHandle.querySink
            .map { it.text.isNotEmpty() }
            .distinctUntilChanged()
            .map { enabled ->
                if (enabled) {
                    // lambda
                    ::clearField
                } else {
                    null
                }
            },
    )

    // Keyboard shortcuts
    interceptKeyEvents(
        // Ctrl+Alt+F: Focus search field
        KeyShortcut(
            key = Key.F,
            isCtrlPressed = true,
            isAltPressed = true,
        ) to flowOf(true)
            .map { enabled ->
                if (enabled) {
                    // lambda
                    {
                        clearField()
                        focusField()
                    }
                } else {
                    null
                }
            },
        // Ctrl+N: Create new cipher
        KeyShortcut(
            key = Key.N,
            isCtrlPressed = true,
        ) to canWriteFlow
            .map { enabled ->
                if (enabled) {
                    // lambda
                    {
                        val autofill = when (mode) {
                            is AppMode.Main -> null
                            is AppMode.QuickSearch -> null
                            is AppMode.SavePasskey -> null
                            is AppMode.PickPasskey -> null
                            is AppMode.SavePassword -> null
                            is AppMode.Save -> {
                                AddRoute.Args.Autofill.leof(mode.args)
                            }

                            is AppMode.Pick -> {
                                AddRoute.Args.Autofill.leof(mode.args)
                            }
                        }
                        val route = LeAddRoute(
                            args = AddRoute.Args(
                                type = DSecret.Type.Login,
                                autofill = autofill,
                                name = null, //queryTrimmed.takeIf { it.isNotEmpty() },
                            ),
                        )
                        val intent = NavigationIntent.NavigateToRoute(route)
                        navigate(intent)
                    }
                } else {
                    null
                }
            },
    )

    val cipherSink = EventFlow<DSecret>()

    val itemSink = mutablePersistedFlow(VaultListPersistence.KEY_ITEM) { "" }

    val selectionHandle = selectionHandle(VaultListPersistence.KEY_SELECTION)
    val itemLocalStateSource = VaultItem2.Item.LocalStateSource.Shared(
        stateFlow = combine(
            selectionHandle.idsFlow,
            itemSink,
        ) { selectedIds, openedId ->
            VaultItem2.Item.SharedState(
                selectedIds = selectedIds.toPersistentSet(),
                openedId = openedId.takeIf { it.isNotEmpty() },
            )
        }.stateIn(
            scope = this,
            started = SharingStarted.Eagerly,
            initialValue = VaultItem2.Item.SharedState(
                selectedIds = selectionHandle.idsFlow.value.toPersistentSet(),
                openedId = itemSink.value.takeIf { it.isNotEmpty() },
            ),
        ),
        onToggleSelection = selectionHandle::toggleSelection,
    )
    // Automatically remove selection from ciphers
    // that do not exist anymore.
    ciphersRawFlow
        .onEach { ciphers ->
            val selectedItemIds = selectionHandle.idsFlow.value
            val filteredSelectedItemIds = selectedItemIds
                .filter { itemId ->
                    val cipher = ciphers.firstOrNull { it.id == itemId }
                    if (cipher == null) return@filter false
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

    val sortDefault = ComparatorHolder(
        comparator = AlphabeticalSort,
        favourites = true,
    )
    // Alternative sort sink that is stored on the
    // disk storage. Mirrored from the in-memory sink.
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
    // Copy the in-memory sorting method into
    // the persistent storage. We need it for
    // 'Remember sorting method' option to work.
    sortSink
        .onEach { value ->
            sortPersistentSink.value = value
        }
        .launchIn(screenScope)

    var scrollPositionKey: Any? = null
    val scrollPositionSink = mutablePersistedFlow<ScrollPositionState>(
        VaultListPersistence.KEY_SCROLL_STATE,
    ) { ScrollPositionState() }

    val filterResult = createFilter(addCipherFilter, confirmationRouteFactory)
    val actionsFlow = vaultListToolbarFlow(
        args = args,
        folderId = fffFolderId,
        showKeyboardSink = showKeyboardSink,
        rememberSortSink = rememberSortSink,
        syncFlow = syncFlow,
        getFolders = getFolders,
        foldersFlow = foldersFlow,
        queueSyncAll = queueSyncAll,
        clearVaultSession = clearVaultSession,
        onRename = { folders ->
            vaultRenameFoldersAction(
                folders = folders,
                confirmationRouteFactory = confirmationRouteFactory,
                renameFolderById = renameFolderById,
            )
        },
    )

    data class ConfigMapper(
        val concealFields: Boolean,
        val appIcons: Boolean,
        val websiteIcons: Boolean,
        val writeCapability: WriteCapability,
    )

    data class AccountAvailability(
        val value: Boolean?,
    )

    val configFlow = combine(
        sessionInputs.concealFields,
        sessionInputs.appIcons,
        sessionInputs.websiteIcons,
        writeCapabilityFlow,
    ) { concealFields, appIcons, websiteIcons, writeCapability ->
        ConfigMapper(
            concealFields = concealFields,
            appIcons = appIcons,
            websiteIcons = websiteIcons,
            writeCapability = writeCapability,
        )
    }.distinctUntilChanged()
    val organizationsByIdFlow = sessionInputs.organizations
        .map { organizations ->
            organizations
                .associateBy { it.id }
        }
        .onStart {
            emit(emptyMap())
        }
        .distinctUntilChanged()

    val ciphersFlow = combine(
        ciphersRawFlow,
        organizationsByIdFlow,
        configFlow,
    ) { secrets, organizationsById, cfg -> Triple(secrets, organizationsById, cfg) }
        .mapLatest { (secrets, organizationsById, cfg) ->
            val items = filterVaultCiphersForMode(
                ciphers = secrets,
                mode = mode,
                args = args,
                passkeyTargetCheck = passkeyTargetCheck,
            )
                .map { secret ->
                    val badgeTapActions = buildVaultBadgeTapActions(
                        mode = mode,
                        secret = secret,
                        passkeyTargetCheck = passkeyTargetCheck,
                        passkeysCredentialViewRouteFactory = passkeysCredentialViewRouteFactory,
                    )
                    val item = secret.toVaultListItem(
                        copy = copy,
                        translator = this@vaultListScreenStateProducer,
                        getTotpCode = getTotpCode,
                        concealFields = cfg.concealFields,
                        appIcons = cfg.appIcons,
                        websiteIcons = cfg.websiteIcons,
                        organizationsById = organizationsById,
                        localStateSource = itemLocalStateSource,
                        onClick = { actions ->
                            buildVaultItemModeMenu(
                                mode = mode,
                                secret = secret,
                                copyActions = actions,
                                canWrite = cfg.writeCapability == WriteCapability.Allowed,
                                cipherSink = cipherSink,
                            )
                        },
                        onClickAttachment = badgeTapActions.onClickAttachment,
                        onClickPasskey = badgeTapActions.onClickPasskey,
                        onClickPassword = badgeTapActions.onClickPassword,
                    )
                    item
                }
                .run {
                    if (args.filter != null) {
                        val ciphers = map { it.source }
                        val predicate = args.filter.prepare(filterContext, ciphers)
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

    val queryTrimmedFlow = queryHandle.queryPairFlow
    val querySearchContextFlow = queryHandle.searchContextFlow
    val queryRevisionFlow = queryHandle.queryRevisionFlow

    data class Rev<T>(
        val count: Int,
        val list: List<T>,
        val revision: Int = 0,
    )

    val autofillTarget = mode.autofillTarget
    val ciphersFilteredStateFlow = createFilteredCiphersFlow(
        filterContext = filterContext,
        ciphersFlow = ciphersFlow,
        orderFlow = sortSink,
        filterFlow = filterResult.filterFlow,
        querySearchContextFlow = querySearchContextFlow,
        autofillTarget = autofillTarget,
        equivalentDomainsBuilderFactory = equivalentDomainsBuilderFactory,
        getSuggestions = getSuggestions,
        dateFormatter = dateFormatter,
        highlightBackgroundColor = highlightBackgroundColor,
        highlightContentColor = highlightContentColor,
    )
        .shareIn(this, SharingStarted.WhileSubscribed(), replay = 1)

    val ciphersFilteredFlow = ciphersFilteredStateFlow
        .map {
            val l = trimVaultItemBadges(
                list = it.list,
                argsFilter = args.filter,
                filterConfig = it.filterConfig,
                orderConfig = it.orderConfig,
                mode = mode,
            )

            Rev(
                count = it.count,
                list = l,
                revision = vaultListStructureRevision(
                    filterConfig = it.filterConfig,
                    queryConfig = it.queryConfig,
                    orderConfig = it.orderConfig,
                ),
            )
        }
        .flowOn(Dispatchers.Default)
        .shareIn(this, SharingStarted.WhileSubscribed(), replay = 1)

    if (!isRelease) vaultSearchTraceFlow(
        surface = VAULT_SEARCH_SURFACE_VAULT_LIST,
        debouncedQueryFlow = queryHandle.debouncedQueryFlow,
        searchContextFlow = querySearchContextFlow,
        rawItemCountFlow = ciphersRawFlow.map { it.size },
        routeFilteredCountFlow = ciphersFlow.map { it.size },
        filterFilteredCountFlow = ciphersFilteredStateFlow.map { it.preQueryCount },
        preferredCountFlow = ciphersFilteredStateFlow.map { it.preQueryPreferredCount },
        activeSortFlow = sortSink.map { sort ->
            formatSortForTrace(
                sortId = sort.comparator.id,
                reversed = sort.reversed,
                favorites = sort.favourites,
            )
        },
        finalResultCountFlow = ciphersFilteredStateFlow.map { it.count },
    )
        .onEach { event ->
            event?.let(searchTraceSink::surface)
        }
        .launchIn(this)

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
    // Defer lazy filter-metadata subscriptions (folders, tags, collections,
    // custom filters, and section state) until the privacy-filtered cipher
    // source emits once, so they do not compete with the critical cipher load.
    val filterListFlow = ciphersRawFlow
        .take(1)
        .flatMapLatest {
            createFilterItemsFlow(
                getCipherFilters = getCipherFilters,
                outputGetter = { it.source },
                outputFlow = ciphersFilteredFlow
                    .map { state ->
                        state.list.mapNotNull { it as? VaultItem2.Item }
                },
                accountGetter = ::identity,
                accountFlow = sessionInputs.accounts,
                profileFlow = sessionInputs.profiles,
                cipherGetter = {
                    it.source
                },
                cipherFlow = ciphersFlow,
                folderGetter = ::identity,
                folderFlow = foldersFlow,
                tagGetter = ::identity,
                tagFlow = tagsFlow,
                collectionGetter = ::identity,
                collectionFlow = sessionInputs.collections,
                organizationGetter = ::identity,
                organizationFlow = sessionInputs.organizations,
                input = filterResult,
                params = FilterParams(
                    deeplinkCustomFilterFlow = deeplinkCustomFilterFlow,
                ),
            )
        }
        .stateIn(this, SharingStarted.WhileSubscribed(), OurFilterResult())

    val selectionFlow = createCipherSelectionFlow(
        selectionHandle = selectionHandle,
        ciphersFlow = ciphersRawFlow,
        collectionsFlow = sessionInputs.collections,
        canWriteFlow = canWriteFlow,
        confirmationRouteFactory = confirmationRouteFactory,
        toolbox = toolbox,
    )

    val itemsFlow = ciphersFilteredFlow
        .map {
            val list = it.list
                .toMutableList()
            if (args.canQuickFilter) {
                list.add(
                    0,
                    VaultItem2.QuickFilters(
                        id = VAULT_QUICK_FILTERS_ID,
                        items = persistentListOf(),
                    ),
                )
            }
            it.copy(list = list)
        }
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

            val a = object : VaultListState.Content.Items.Revision.Mutable<Pair<Int, Int>> {
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
            val b = object : VaultListState.Content.Items.Revision.Mutable<Int> {
                override val value: Int
                    get() = a.value.first
            }
            val c = object : VaultListState.Content.Items.Revision.Mutable<Int> {
                override val value: Int
                    get() = a.value.second
            }
            VaultListState.Content.Items(
                onSelected = { key ->
                    itemSink.value = key.orEmpty()
                },
                revision = VaultListState.Content.Items.Revision(
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
    val queryStateFlow = combine(
        queryTrimmedFlow,
        queryRevisionFlow,
        queryHandle.queryHighlightingFlow,
        queryHandle.queryQualifierSuggestionFlow,
    ) { queryPair, queryRevision, queryHighlighting, queryQualifierSuggestion ->
        QueryStateData(
            queryPair = queryPair,
            queryRevision = queryRevision,
            queryHighlighting = queryHighlighting,
            queryQualifierSuggestion = queryQualifierSuggestion,
        )
    }
    val comparatorStateFlow = comparatorsListFlow
        .combine(sortSink) { a, b -> a to b }
    val hasAccountsFlow = sessionInputs.accounts
        .map { accounts ->
            AccountAvailability(accounts.isNotEmpty())
        }
        .distinctUntilChanged()
        .onStart {
            emit(AccountAvailability(null))
        }
    val writeActionsFlow = combine(
        writeCapabilityFlow,
        selectionHandle.idsFlow,
    ) { capability, itemIds ->
        vaultListWriteActionPolicy(
            capability = capability,
            selectionActive = itemIds.isNotEmpty(),
        )
    }
    val finalActionsFlow = ciphersRawFlow
        .take(1)
        .flatMapLatest {
            actionsFlow
        }
        .onStart {
            emit(persistentListOf())
        }
    val finalShowKeyboardFlow = showKeyboardSink
    return combine(
        itemsNullableFlow,
        filterListFlow,
        comparatorStateFlow,
        queryStateFlow,
        hasAccountsFlow,
    ) { itemsContent, filters, comparatorState, queryStateData, accountAvailability ->
        val hasAccounts = accountAvailability.value
        val (comparators, sort) = comparatorState
        val queryPair = queryStateData.queryPair
        val queryRevision = queryStateData.queryRevision
        val queryHighlighting = queryStateData.queryHighlighting
        val queryQualifierSuggestion = queryStateData.queryQualifierSuggestion
        val (queryCell, queryTrimmed) = queryPair
        val query = queryCell.text
        val revision = filters.rev xor queryRevision xor sort.hashCode()
        val content = when {
            hasAccounts == false -> VaultListState.Content.AddAccount(
                onAddAccount = { type ->
                    val routeMain = when (type) {
                        AccountType.BITWARDEN -> bitwardenLoginRouteFactory.create()
                        AccountType.KEEPASS -> KeePassLoginRoute
                    }
                    val route = registerRouteResultReceiver(routeMain) {
                        // Close the login screen.
                        navigate(NavigationIntent.Pop)
                    }
                    navigate(NavigationIntent.NavigateToRoute(route))
                },
            )

            itemsContent is VaultListState.Content.Items || hasAccounts == true ->
                itemsContent ?: VaultListState.Content.Skeleton

            else -> VaultListState.Content.Skeleton
        }
        val queryField = if (content !is VaultListState.Content.AddAccount) {
            // We want to let the user search while the items
            // are still loading.
            TextFieldModel(
                text = query,
                textRevision = queryCell.revision,
                id = "query",
                onChange = queryHandle::onChange,
                onSetText = queryHandle::setText,
                focusFlow = queryHandle.queryFocusSink,
            )
        } else {
            TextFieldModel(
                text = "",
            )
        }

        val primaryActions = buildVaultCreateActions(
            mode = mode,
            queryTrimmed = queryTrimmed,
        )
        val hasRenderableItems =
            hasAccounts == true ||
                itemsContent is VaultListState.Content.Items
        val shouldShowPrimaryActions =
            content !is VaultListState.Content.AddAccount && hasRenderableItems
        VaultListState(
            revision = revision,
            query = queryField,
            queryHighlighting = if (content !is VaultListState.Content.AddAccount) {
                queryHighlighting
            } else {
                QueryHighlighting.Empty
            },
            queryQualifierSuggestion = queryQualifierSuggestion?.text,
            onQueryQualifierSuggestion = queryQualifierSuggestion
                ?.let { suggestion ->
                    { rawQuery ->
                        applyVaultSearchQualifierSuggestion(
                            query = rawQuery,
                            suggestion = suggestion,
                        )
                    }
                },
            filters = filters.items,
            sort = comparators
                .takeIf { queryTrimmed.isEmpty() }
                .orEmpty(),
            primaryActions = if (shouldShowPrimaryActions) {
                primaryActions
            } else {
                emptyList()
            },
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
    }.combine(writeActionsFlow) { state, writeActionPolicy ->
        when (writeActionPolicy) {
            VaultListWriteActionPolicy.Hide -> state.copy(
                primaryActions = emptyList(),
            )

            VaultListWriteActionPolicy.Allow -> state
            VaultListWriteActionPolicy.ShowSubscription -> {
                if (state.primaryActions.isEmpty()) {
                    return@combine state
                }
                val primaryActions = buildVaultCreatePaywallActions()
                state.copy(
                    primaryActions = primaryActions,
                )
            }
        }
    }.combine(finalActionsFlow) { state, actions ->
        state.copy(
            actions = actions,
        )
    }.combine(finalShowKeyboardFlow) { state, showKeyboard ->
        state.copy(
            showKeyboard = showKeyboard && state.query.onChange != null,
        )
    }
}

private data class QueryStateData(
    val queryPair: Pair<TextCell, String>,
    val queryRevision: Int,
    val queryHighlighting: QueryHighlighting,
    val queryQualifierSuggestion: VaultSearchQualifierSuggestion?,
)

private data class FilteredList<T>(
    val count: Int,
    val list: List<T>,
    val preferredList: List<T>?,
    val preQueryCount: Int = count,
    val preQueryPreferredCount: Int? = preferredList?.size,
    val orderConfig: ComparatorHolder? = null,
    val filterConfig: FilterHolder? = null,
    val queryConfig: CompiledQueryPlan? = null,
)

private data class Preferences(
    val appId: String? = null,
    val webDomain: String? = null,
)

private fun createFilteredCiphersFlow(
    filterContext: CipherFilterContext,
    ciphersFlow: Flow<List<VaultItem2.Item>>,
    orderFlow: Flow<ComparatorHolder>,
    filterFlow: Flow<FilterHolder>,
    querySearchContextFlow: Flow<VaultSearchContext?>,
    autofillTarget: AutofillTarget?,
    equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
    getSuggestions: GetSuggestions<Any?>,
    dateFormatter: DateFormatter,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
) = ciphersFlow
    .map { items ->
        val preferredList = if (autofillTarget != null) {
            buildVaultPreferredItems(
                items = items,
                autofillTarget = autofillTarget,
                getSuggestions = getSuggestions,
                equivalentDomainsBuilderFactory = equivalentDomainsBuilderFactory,
            )
        } else {
            null
        }
        FilteredList(
            count = items.size,
            list = items,
            preferredList = preferredList,
            preQueryCount = items.size,
            preQueryPreferredCount = preferredList?.size,
        )
    }
    .combine(
        flow = orderFlow
            .map { orderConfig ->
                val orderComparator = buildVaultComparator(orderConfig)
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

        val filteredAllItems = filterVaultItems(
            filterContext = filterContext,
            items = state.list,
            filter = filterConfig.filter,
        )
        val filteredPreferredItems = state
            .preferredList
            ?.let { preferredItems ->
                filterVaultItems(
                    filterContext = filterContext,
                    items = preferredItems,
                    filter = filterConfig.filter,
                )
            }
        state.copy(
            list = filteredAllItems,
            preferredList = filteredPreferredItems,
            preQueryCount = filteredAllItems.size,
            preQueryPreferredCount = filteredPreferredItems?.size,
            filterConfig = filterConfig,
        )
    }
    .combine(querySearchContextFlow) { state, queryContext ->
        state to queryContext
    }
    .mapLatest { (state, queryContext) ->
        if (queryContext == null) {
            return@mapLatest FilteredList(
                count = state.list.size,
                list = state.list,
                preferredList = state.preferredList,
                preQueryCount = state.preQueryCount,
                preQueryPreferredCount = state.preQueryPreferredCount,
                orderConfig = state.orderConfig,
                filterConfig = state.filterConfig,
                queryConfig = null,
            )
        }

        val filteredAllItems = queryVaultItems(
            searchContext = queryContext,
            items = state.list,
            highlightBackgroundColor = highlightBackgroundColor,
            highlightContentColor = highlightContentColor,
        )
        val filteredPreferredItems = state.preferredList
            ?.let { preferredItems ->
                queryVaultItems(
                    searchContext = queryContext,
                    items = preferredItems,
                    highlightBackgroundColor = highlightBackgroundColor,
                    highlightContentColor = highlightContentColor,
                )
            }
        FilteredList(
            count = filteredAllItems.size,
            list = filteredAllItems,
            preferredList = filteredPreferredItems,
            preQueryCount = state.preQueryCount,
            preQueryPreferredCount = state.preQueryPreferredCount,
            orderConfig = state.orderConfig,
            filterConfig = state.filterConfig,
            queryConfig = queryContext.queryPlan,
        )
    }
    .map { state ->
        val items = decorateVaultItems(
            list = state.list,
            preferredList = state.preferredList,
            orderConfig = state.orderConfig,
            queryConfig = state.queryConfig,
            dateFormatter = dateFormatter,
        )
        FilteredList(
            count = state.list.size,
            list = items,
            preferredList = items,
            orderConfig = state.orderConfig,
            filterConfig = state.filterConfig,
            queryConfig = state.queryConfig,
        )
    }

internal fun pruneVaultListItemPresentation(
    list: List<VaultItem2>,
    keepOtp: Boolean,
    keepPasskey: Boolean,
    keepPassword: Boolean,
    keepAttachment: Boolean,
): List<VaultItem2> {
    if (
        keepOtp &&
        keepPasskey &&
        keepPassword &&
        keepAttachment
    ) {
        return list
    }

    return list.mapIndexed { index, item ->
        when (item) {
            is VaultItem2.Item -> {
                val shapeState = getShapeState(
                    list = list,
                    index = index,
                    predicate = { element, _ -> element is VaultItem2.Item },
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
