package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import arrow.core.identity
import arrow.core.partially1
import arrow.optics.Getter
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.autofillTarget
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.nullable
import com.artemchep.keyguard.common.io.parallelSearch
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.formatH
import com.artemchep.keyguard.common.model.iconImageVector
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
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
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.RenameFolderById
import com.artemchep.keyguard.common.usecase.SupervisorRead
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.AttachmentsRoute
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.login.LoginRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.decorator.ItemDecorator
import com.artemchep.keyguard.feature.decorator.ItemDecoratorDate
import com.artemchep.keyguard.feature.decorator.ItemDecoratorNone
import com.artemchep.keyguard.feature.decorator.ItemDecoratorTitle
import com.artemchep.keyguard.feature.duplicates.list.createCipherSelectionFlow
import com.artemchep.keyguard.feature.filter.CipherFiltersRoute
import com.artemchep.keyguard.feature.generator.history.mapLatestScoped
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.LeAddRoute
import com.artemchep.keyguard.feature.home.vault.component.obscurePassword
import com.artemchep.keyguard.feature.home.vault.model.SortItem
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.home.vault.search.find
import com.artemchep.keyguard.feature.home.vault.search.findAlike
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.home.vault.search.sort.LastCreatedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.LastModifiedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordLastModifiedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordStrengthSort
import com.artemchep.keyguard.feature.home.vault.search.sort.Sort
import com.artemchep.keyguard.feature.home.vault.util.AlphabeticalSortMinItemsSize
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRoute
import com.artemchep.keyguard.feature.search.search.SEARCH_DEBOUNCE
import com.artemchep.keyguard.leof
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.SyncIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.icons.iconSmall
import com.artemchep.keyguard.ui.selection.selectionHandle
import org.jetbrains.compose.resources.StringResource
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    val comparator: Sort,
    val reversed: Boolean = false,
    val favourites: Boolean = false,
) : LeParcelable {
    companion object {
        fun of(map: Map<String, Any?>): ComparatorHolder {
            return ComparatorHolder(
                comparator = Sort.valueOf(map["comparator"].toString()) ?: AlphabeticalSort,
                reversed = map["reversed"].toString() == "true",
                favourites = map["favourites"].toString() == "true",
            )
        }

        fun deserialize(
            json: Json,
            value: Map<String, Any?>,
        ): ComparatorHolder = of(value)

        fun serialize(
            json: Json,
            value: ComparatorHolder,
        ): Map<String, Any?> = value.toMap()
    }

    fun toMap() = mapOf(
        "comparator" to comparator.id,
        "reversed" to reversed,
        "favourites" to favourites,
    )
}

@Composable
fun vaultListScreenState(
    args: VaultRoute.Args,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
    mode: AppMode,
): VaultListState = with(localDI().direct) {
    vaultListScreenState(
        directDI = this,
        args = args,
        highlightBackgroundColor = highlightBackgroundColor,
        highlightContentColor = highlightContentColor,
        mode = mode,
        deeplinkService = instance(),
        clearVaultSession = instance(),
        getSuggestions = instance(),
        getAccounts = instance(),
        getProfiles = instance(),
        getCanWrite = instance(),
        getCiphers = instance(),
        getFolders = instance(),
        getCollections = instance(),
        getOrganizations = instance(),
        getTotpCode = instance(),
        getConcealFields = instance(),
        getAppIcons = instance(),
        getWebsiteIcons = instance(),
        getPasswordStrength = instance(),
        getCipherOpenedHistory = instance(),
        passkeyTargetCheck = instance(),
        renameFolderById = instance(),
        toolbox = instance(),
        queueSyncAll = instance(),
        syncSupervisor = instance(),
        dateFormatter = instance(),
        clipboardService = instance(),
    )
}

@Composable
fun vaultListScreenState(
    directDI: DirectDI,
    args: VaultRoute.Args,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
    mode: AppMode,
    deeplinkService: DeeplinkService,
    getSuggestions: GetSuggestions<Any?>,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getCanWrite: GetCanWrite,
    getCiphers: GetCiphers,
    getFolders: GetFolders,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
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
): VaultListState = produceScreenState(
    key = "vault_list",
    initial = VaultListState(),
    args = arrayOf(
        getAccounts,
        getCiphers,
        getTotpCode,
        dateFormatter,
        clipboardService,
    ),
) {
    val storage = kotlin.run {
        val disk = loadDiskHandle("vault.list")
        PersistedStorage.InDisk(disk)
    }

    val fffFilter = args.filter ?: DFilter.All
    println(fffFilter)
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

    fun onRename(
        folders: List<DFolder>,
    ) = action {
        val route = registerRouteResultReceiver(
            route = ConfirmationRoute(
                args = ConfirmationRoute.Args(
                    icon = icon(Icons.Outlined.Edit),
                    title = if (folders.size > 1) {
                        translate(Res.string.folder_action_change_names_title)
                    } else {
                        translate(Res.string.folder_action_change_name_title)
                    },
                    items = folders
                        .sortedWith(StringComparatorIgnoreCase { it.name })
                        .map { folder ->
                            ConfirmationRoute.Args.Item.StringItem(
                                key = folder.id,
                                value = folder.name,
                                title = folder.name,
                                type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
                                canBeEmpty = false,
                            )
                        },
                ),
            ),
        ) {
            if (it is ConfirmationResult.Confirm) {
                val folderIdsToNames = it.data
                    .mapValues { it.value as String }
                renameFolderById(folderIdsToNames)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    val copy = copy(clipboardService)

    val ciphersRawFlow = filterHiddenProfiles(
        getProfiles = getProfiles,
        getCiphers = getCiphers,
        filter = args.filter,
    )

    val querySink = mutablePersistedFlow("query") { "" }
    val queryState = mutableComposeState(querySink)

    // Intercept the back button while the
    // search query is not empty.
    interceptBackPress(
        interceptorFlow = querySink
            .map { it.isNotEmpty() }
            .distinctUntilChanged()
            .map { enabled ->
                if (enabled) {
                    // lambda
                    {
                        queryState.value = ""
                    }
                } else {
                    null
                }
            },
    )

    val cipherSink = EventFlow<DSecret>()

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

    val showKeyboardSink = mutablePersistedFlow(
        key = "keyboard",
        storage = if (args.canAlwaysShowKeyboard) {
            storage
        } else PersistedStorage.InMemory,
    ) { false }
    val rememberSortSink = mutablePersistedFlow(
        key = "sort_persistent_enabled",
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
    val scrollPositionSink = mutablePersistedFlow<OhOhOh>("scroll_state") { OhOhOh() }

    val filterResult = createFilter(directDI)
    val actionsFlow = kotlin.run {
        val actionTrashItem = FlatItemAction(
            leading = {
                Icon(Icons.Outlined.Delete, null)
            },
            title = Res.string.trash.wrap(),
            trailing = {
                ChevronIcon()
            },
            onClick = onClick {
                val newArgs = args.copy(
                    appBar = VaultRoute.Args.AppBar(
                        subtitle = args.appBar?.subtitle
                            ?: translate(Res.string.home_vault_label),
                        title = translate(Res.string.trash),
                    ),
                    trash = true,
                    preselect = false,
                    canAddSecrets = false,
                )
                val route = VaultListRoute(newArgs)
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
            },
        )
        val actionTrashFlow = flowOf(actionTrashItem)
        val actionDownloadsItem = FlatItemAction(
            leading = {
                Icon(Icons.Outlined.Download, null)
            },
            title = Res.string.downloads.wrap(),
            trailing = {
                ChevronIcon()
            },
            onClick = {
                val route = AttachmentsRoute()
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
            },
        )
        val actionDownloadsFlow = flowOf(actionDownloadsItem)
        val actionFiltersItem = CipherFiltersRoute.actionOrNull(
            translator = this,
            navigate = ::navigate,
        )
        val actionFiltersFlow = flowOf(actionFiltersItem)
        val actionGroupFlow = combine(
            actionTrashFlow,
            actionDownloadsFlow,
            actionFiltersFlow,
        ) { array ->
            buildContextItems {
                section {
                    array.forEach(this::plusAssign)
                }
            }
        }

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
        val actionRememberSortingFlow = rememberSortSink
            .map { rememberSorting ->
                FlatItemAction(
                    leading = {
                        Icon(
                            Icons.Outlined.SortByAlpha,
                            null,
                        )
                    },
                    trailing = {
                        Switch(
                            checked = rememberSorting,
                            onCheckedChange = rememberSortSink::value::set,
                        )
                    },
                    title = Res.string.vault_action_remember_sorting_title.wrap(),
                    onClick = rememberSortSink::value::set.partially1(!rememberSorting),
                )
            }
        val actionGroup2Flow = combine(
            actionAlwaysShowKeyboardFlow,
            actionRememberSortingFlow,
        ) { array ->
            buildContextItems {
                section {
                    array.forEach(this::plusAssign)
                }
            }
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
                clearVaultSession()
                    .launchIn(appScope)
            },
        )
        val actionLockVaultFlow = flowOf(actionLockVaultItem)
        val actionGroup3Flow = combine(
            actionSyncAccountsFlow,
            actionLockVaultFlow,
        ) { array ->
            buildContextItems {
                section {
                    array.forEach(this::plusAssign)
                }
            }
        }
        val actionFolderRenameFlow = getFolders()
            .map { folders ->
                val folder = folders.firstOrNull { it.id == fffFolderId }
                if (folder != null) {
                    FlatItemAction(
                        leading = {
                            Icon(Icons.Outlined.Edit, null)
                        },
                        title = Res.string.vault_action_rename_folder_title.wrap(),
                        onClick = {
                            onRename(listOf(folder))
                        },
                    )
                } else {
                    null
                }
            }
            .map {
                buildContextItems {
                    this += it
                }
            }
        if (args.canAlwaysShowKeyboard) {
            combine(
                actionFolderRenameFlow,
                actionGroupFlow,
                actionGroup2Flow,
                actionGroup3Flow,
            ) { array ->
                buildContextItems {
                    array.forEach {
                        section {
                            it.forEach {
                                this += it
                            }
                        }
                    }
                }
            }
        } else {
            combine(
                actionFolderRenameFlow,
            ) { array ->
                buildContextItems {
                    array.forEach {
                        section {
                            it.forEach {
                                this += it
                            }
                        }
                    }
                }
            }
        }
    }

    val recentState = MutableStateFlow(
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
                .run {
                    if (mode is AppMode.PickPasskey) {
                        return@run this
                            .filter { cipher ->
                                val credentials = cipher.login?.fido2Credentials.orEmpty()
                                credentials
                                    .any { credential ->
                                        passkeyTargetCheck(credential, mode.target)
                                            .attempt()
                                            .bind()
                                            .isRight { it }
                                    }
                            }
                    }
                    if (mode is AppMode.HasType) {
                        val type = mode.type
                        if (type != null) {
                            return@run this
                                .filter { it.type == type }
                        }
                    }

                    this
                }
                .filter {
                    when (args.trash) {
                        true -> it.deletedDate != null
                        false -> it.deletedDate == null
                        null -> true
                    }
                }
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
                            val dropdown = when (mode) {
                                is AppMode.Pick -> buildContextItems {
                                    section {
                                        this += FlatItemAction(
                                            icon = Icons.Outlined.AutoAwesome,
                                            title = Res.string.autofill.wrap(),
                                            onClick = {
                                                val extra = AppMode.Pick.Extra()
                                                mode.onAutofill(secret, extra)
                                            },
                                        )
                                        if (cfg.canWrite) {
                                            this += FlatItemAction(
                                                leading = iconSmall(
                                                    Icons.Outlined.AutoAwesome,
                                                    Icons.Outlined.Save,
                                                ),
                                                title = Res.string.autofill_and_save_uri.wrap(),
                                                onClick = {
                                                    val extra = AppMode.Pick.Extra(
                                                        forceAddUri = true,
                                                    )
                                                    mode.onAutofill(secret, extra)
                                                },
                                            )
                                        }
                                    }
                                    section {
                                        actions.forEach { action ->
                                            this += action
                                        }
                                    }
                                    section {
                                        this += FlatItemAction(
                                            icon = Icons.Outlined.Info,
                                            title = Res.string.ciphers_view_details.wrap(),
                                            trailing = {
                                                ChevronIcon()
                                            },
                                            onClick = {
                                                cipherSink.emit(secret)
                                            },
                                        )
                                    }
                                }

                                is AppMode.Save -> buildContextItems {
                                    section {
                                        this += FlatItemAction(
                                            icon = Icons.Outlined.Save,
                                            title = Res.string.ciphers_save_to.wrap(),
                                            onClick = {
                                                val route = LeAddRoute(
                                                    args = AddRoute.Args(
                                                        behavior = AddRoute.Args.Behavior(
                                                            // User wants to quickly check the updated
                                                            // cipher data, not to fill the data :P
                                                            autoShowKeyboard = false,
                                                            launchEditedCipher = false,
                                                        ),
                                                        initialValue = secret,
                                                        autofill = AddRoute.Args.Autofill.leof(mode.args),
                                                    ),
                                                )
                                                val intent = NavigationIntent.NavigateToRoute(route)
                                                navigate(intent)
                                            },
                                        )
                                    }
                                    section {
                                        this += FlatItemAction(
                                            icon = Icons.Outlined.Info,
                                            title = Res.string.ciphers_view_details.wrap(),
                                            trailing = {
                                                ChevronIcon()
                                            },
                                            onClick = {
                                                cipherSink.emit(secret)
                                            },
                                        )
                                    }
                                }

                                is AppMode.SavePasskey -> buildContextItems {
                                    section {
                                        this += FlatItemAction(
                                            icon = Icons.Outlined.Save,
                                            title = Res.string.ciphers_save_to.wrap(),
                                            onClick = {
                                                mode.onComplete(secret)
                                            },
                                        )
                                    }
                                    section {
                                        this += FlatItemAction(
                                            icon = Icons.Outlined.Info,
                                            title = Res.string.ciphers_view_details.wrap(),
                                            trailing = {
                                                ChevronIcon()
                                            },
                                            onClick = {
                                                cipherSink.emit(secret)
                                            },
                                        )
                                    }
                                }

                                is AppMode.PickPasskey ->
                                    return@toVaultListItem VaultItem2.Item.Action.Go(
                                        onClick = cipherSink::emit.partially1(secret),
                                    )

                                is AppMode.Main ->
                                    return@toVaultListItem VaultItem2.Item.Action.Go(
                                        onClick = cipherSink::emit.partially1(secret),
                                    )
                            }
                            VaultItem2.Item.Action.Dropdown(
                                actions = dropdown,
                            )
                        },
                        onClickAttachment = { attachment ->
                            // lambda
                            {
                                // Do nothing
                            }
                        },
                        onClickPasskey = { credential ->
                            if (mode is AppMode.PickPasskey) {
                                val matches = passkeyTargetCheck(credential, mode.target)
                                    .attempt()
                                    .bind()
                                    .isRight { it }
                                if (matches) {
                                    // lambda
                                    {
                                        mode.onComplete(credential)
                                    }
                                } else {
                                    null
                                }
                            } else {
                                // lambda
                                {
                                    val route = PasskeysCredentialViewRoute(
                                        args = PasskeysCredentialViewRoute.Args(
                                            cipherId = secret.id,
                                            credentialId = credential.credentialId,
                                            model = credential,
                                        ),
                                    )
                                    val intent = NavigationIntent.NavigateToRoute(route)
                                    navigate(intent)
                                }
                            }
                        },
                    )
                    val indexed = mutableListOf<SearchToken>()
                    indexed += SearchToken(
                        priority = 2f,
                        value = secret.name,
                    )
                    if (secret.login != null) {
                        // Make username searchable
                        if (secret.login.username != null) {
                            indexed += SearchToken(
                                priority = 1f,
                                value = secret.login.username,
                            )
                        }
                        // Make password searchable
                        if (!secret.reprompt && secret.login.password != null) {
                            indexed += SearchToken(
                                priority = 0.5f,
                                value = secret.login.password,
                            )
                        }
                        secret.login.fido2Credentials.forEach {
                            if (it.userDisplayName != null) {
                                indexed += SearchToken(
                                    priority = 0.8f,
                                    value = it.userDisplayName,
                                )
                            }
                            indexed += SearchToken(
                                priority = 0.5f,
                                value = it.rpId,
                            )
                        }
                    }
                    if (secret.card != null) {
                        // Make card holder name &
                        // card brand searchable
                        if (secret.card.cardholderName != null) {
                            indexed += SearchToken(
                                priority = 1f,
                                value = secret.card.cardholderName,
                            )
                        }
                        if (secret.card.brand != null) {
                            indexed += SearchToken(
                                priority = 0.5f,
                                value = secret.card.brand,
                            )
                        }
                        if (!secret.reprompt && secret.card.number != null) {
                            indexed += SearchToken(
                                priority = 0.5f,
                                value = secret.card.number,
                            )
                        }
                    }
                    if (secret.identity != null) {
                        // Name
                        if (secret.identity.firstName != null) {
                            indexed += SearchToken(
                                priority = 1f,
                                value = secret.identity.firstName,
                            )
                        }
                        if (secret.identity.middleName != null) {
                            indexed += SearchToken(
                                priority = 0.5f,
                                value = secret.identity.middleName,
                            )
                        }
                        if (secret.identity.lastName != null) {
                            indexed += SearchToken(
                                priority = 1f,
                                value = secret.identity.lastName,
                            )
                        }
                        // Contacts
                        if (secret.identity.email != null) {
                            indexed += SearchToken(
                                priority = 1f,
                                value = secret.identity.email,
                            )
                        }
                        if (secret.identity.phone != null) {
                            indexed += SearchToken(
                                priority = 0.5f,
                                value = secret.identity.phone,
                            )
                        }
                        if (secret.identity.username != null) {
                            indexed += SearchToken(
                                priority = 1f,
                                value = secret.identity.username,
                            )
                        }
                    }
                    if (!secret.reprompt && secret.notes.isNotBlank()) {
                        indexed += SearchToken(
                            priority = 0.8f,
                            value = secret.notes,
                        )
                    }
                    secret.uris.forEach { uri ->
                        @Suppress("MoveVariableDeclarationIntoWhen")
                        val matchType = uri.match ?: DSecret.Uri.MatchType.default
                        val priority = when (matchType) {
                            DSecret.Uri.MatchType.Domain -> 0.5f
                            DSecret.Uri.MatchType.Host -> 0.5f
                            DSecret.Uri.MatchType.StartsWith -> 0.5f
                            DSecret.Uri.MatchType.Exact -> 0.5f
                            DSecret.Uri.MatchType.RegularExpression -> 0f // can't type regex...
                            DSecret.Uri.MatchType.Never -> 0.1f
                        }
                        if (priority > 0f) {
                            indexed += SearchToken(
                                priority = priority,
                                value = uri.uri,
                            )
                        }
                    }
                    secret.fields.forEach { field ->
                        val priority = when (field.type) {
                            DSecret.Field.Type.Text -> 0.5f
                            DSecret.Field.Type.Hidden -> 0f
                            DSecret.Field.Type.Boolean -> 0f
                            DSecret.Field.Type.Linked -> 0f
                        }
                        if (priority > 0f && field.value != null) {
                            indexed += SearchToken(
                                priority = priority,
                                value = field.value,
                            )
                        }
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

    val autofillTarget = mode.autofillTarget
    val ciphersFilteredFlow = hahah(
        directDI = directDI,
        ciphersFlow = ciphersFlow,
        orderFlow = sortSink,
        filterFlow = filterResult.filterFlow,
        queryFlow = queryIndexedFlow,
        autofillTarget = autofillTarget,
        getSuggestions = getSuggestions,
        dateFormatter = dateFormatter,
        highlightBackgroundColor = highlightBackgroundColor,
        highlightContentColor = highlightContentColor,
    )
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
                } != null ||
                    // If a user is in the pick a passkey mode,
                    // then we want to always show it in the items.
                    mode is AppMode.PickPasskey ||
                    mode is AppMode.SavePasskey
            val l = if (keepOtp && keepPasskey) {
                it.list
            } else {
                it.list
                    .map {
                        when (it) {
                            is VaultItem2.Item -> {
                                it.copy(
                                    token = it.token.takeIf { keepOtp },
                                    passkeys = it.passkeys.takeIf { keepPasskey }
                                        ?: persistentListOf(),
                                    attachments2 = it.attachments2.takeIf { keepAttachment }
                                        ?: persistentListOf(),
                                )
                            }

                            else -> it
                        }
                    }
            }

            Rev(
                count = it.count,
                list = l,
                revision = (it.filterConfig?.id ?: 0) xor (
                        it.queryConfig?.text?.hashCode()
                            ?: 0
                        ) xor (it.orderConfig?.hashCode() ?: 0),
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
            it.model.source
        },
        cipherFlow = ciphersFlow,
        folderGetter = ::identity,
        folderFlow = getFolders(),
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

    val selectionFlow = createCipherSelectionFlow(
        selectionHandle = selectionHandle,
        ciphersFlow = ciphersRawFlow,
        collectionsFlow = getCollections(),
        canWriteFlow = getCanWrite(),
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
                        id = "quick_filters",
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
                ?: VaultListState.Content.Skeleton
        } else {
            VaultListState.Content.AddAccount(
                onAddAccount = {
                    val route = registerRouteResultReceiver(LoginRoute()) {
                        // Close the login screen.
                        navigate(NavigationIntent.Pop)
                    }
                    navigate(NavigationIntent.NavigateToRoute(route))
                },
            )
        }
        val queryField = if (content !is VaultListState.Content.AddAccount) {
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

        fun createTypeAction(
            type: DSecret.Type,
        ) = FlatItemAction(
            leading = icon(type.iconImageVector()),
            title = type.titleH().wrap(),
            onClick = {
                val autofill = when (mode) {
                    is AppMode.Main -> null
                    is AppMode.SavePasskey -> null
                    is AppMode.PickPasskey -> null
                    is AppMode.Save -> {
                        AddRoute.Args.Autofill.leof(mode.args)
                    }

                    is AppMode.Pick -> {
                        AddRoute.Args.Autofill.leof(mode.args)
                    }
                }
                val route = LeAddRoute(
                    args = AddRoute.Args(
                        type = type,
                        autofill = autofill,
                        name = queryTrimmed.takeIf { it.isNotEmpty() },
                    ),
                )
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
            },
        )

        val primaryActions = kotlin.run {
            // You can not create a passkey at will, it
            // must be a separate action. During the pick passkey
            // request you can only select existing ones.
            if (mode is AppMode.PickPasskey) {
                return@run emptyList()
            }
            if (mode is AppMode.HasType) {
                val type = mode.type
                if (type != null) {
                    return@run listOf<FlatItemAction>(
                        createTypeAction(
                            type = type,
                        ),
                    )
                }
            }
            listOf(
                createTypeAction(
                    type = DSecret.Type.Login,
                ),
                createTypeAction(
                    type = DSecret.Type.Card,
                ),
                createTypeAction(
                    type = DSecret.Type.Identity,
                ),
                createTypeAction(
                    type = DSecret.Type.SecureNote,
                ),
            )
        }
        VaultListState(
            revision = revision,
            query = queryField,
            filters = filters.items,
            sort = comparators
                .takeIf { queryTrimmed.isEmpty() }
                .orEmpty(),
            primaryActions = if (hasAccounts) {
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
    }.combine(
        combine(
            getCanWrite(),
            selectionHandle.idsFlow,
        ) { canWrite, itemIds ->
            canWrite && itemIds.isEmpty()
        },
    ) { state, canWrite ->
        state.copy(
            primaryActions = state.primaryActions.takeIf { canWrite }.orEmpty(),
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
    val preferredList: List<T>?,
    val orderConfig: ComparatorHolder? = null,
    val filterConfig: FilterHolder? = null,
    val queryConfig: IndexedText? = null,
)

private data class Preferences(
    val appId: String? = null,
    val webDomain: String? = null,
)

private fun hahah(
    directDI: DirectDI,
    ciphersFlow: Flow<List<IndexedModel<VaultItem2.Item>>>,
    orderFlow: Flow<ComparatorHolder>,
    filterFlow: Flow<FilterHolder>,
    queryFlow: Flow<IndexedText?>,
    autofillTarget: AutofillTarget?,
    getSuggestions: GetSuggestions<Any?>,
    dateFormatter: DateFormatter,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
) = ciphersFlow
    .map { items ->
        val preferredList = if (autofillTarget != null) {
            getSuggestions(
                items,
                Getter {
                    val m = it as IndexedModel<VaultItem2.Item>
                    m.model.source
                },
                autofillTarget,
            )
                .bind().let { it as List<IndexedModel<VaultItem2.Item>> }
                .map { item ->
                    val newModel = item.model.copy(
                        id = "preferred." + item.model.id,
                    )
                    IndexedModel(
                        model = newModel,
                        indexedText = item.indexedText,
                        indexedOther = emptyList(),
                    )
                }
        } else {
            null
        }
        FilteredBoo(
            count = items.size,
            list = items,
            preferredList = preferredList,
        )
    }
    .combine(
        flow = orderFlow
            .map { orderConfig ->
                val orderComparator = Comparator<IndexedModel<VaultItem2.Item>> { a, b ->
                    val aModel = a.model
                    val bModel = b.model

                    var r = 0
                    if (r == 0 && orderConfig.favourites) {
                        // Place favourite items on top of the list.
                        r = -compareValues(a.model.favourite, b.model.favourite)
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
                val ciphers = map { it.model.source }
                val predicate = filterConfig.filter.prepare(directDI, ciphers)
                filter { predicate(it.model.source) }
            }
        val filteredPreferredItems = state
            .preferredList
            ?.run {
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
                preferredList = state.preferredList?.map { it.model },
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
        val filteredPreferredItems = state.preferredList?.search(
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
        val keys = mutableSetOf<String>()

        val orderConfig = state.orderConfig
        val decorator = when {
            // Search does not guarantee meaningful order that we can
            // show in the section.
            state.queryConfig != null -> ItemDecoratorNone
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

        val items = run {
            val out = mutableListOf<VaultItem2>()
            if (state.preferredList != null) {
                // We want to show the 'No suggestions' text if the suggestions
                // target does exist, but searching for suggestions returns no
                // items.
                if (state.preferredList.isEmpty()) {
                    out += VaultItem2.NoSuggestions
                }
                state.preferredList.forEach { item ->
                    out += item
                }

                // A section item for all items.
                if (state.list.isNotEmpty()) {
                    val section = VaultItem2.Section(
                        id = "preferred.end",
                        text = TextHolder.Res(Res.string.items_all),
                    )
                    out += section
                }
            }
            state.list.forEach { item ->
                if (!item.favourite || orderConfig?.favourites != true) {
                    val section = decorator.getOrNull(item)
                    if (section != null) out += section
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
            preferredList = items,
            orderConfig = state.orderConfig,
            filterConfig = state.filterConfig,
            queryConfig = state.queryConfig,
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

private suspend fun List<IndexedModel<VaultItem2.Item>>.search(
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
                    { !it.model.favourite },
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
