package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import arrow.core.partially1
import arrow.optics.Getter
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.CipherFilterContext
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.iconImageVector
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.RenameFolderById
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.attachments.AttachmentsRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.filter.CipherFiltersRoute
import com.artemchep.keyguard.feature.home.settings.subscriptions.SubscriptionsSettingsRoute
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.LeAddRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchContext
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledQueryPlan
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordLastModifiedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordStrengthSort
import com.artemchep.keyguard.feature.largetype.LargeTypeRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRoute
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRouteFactory
import com.artemchep.keyguard.leof
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.ContextItemBuilder
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.SwitchExpressive
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.SyncIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.icons.iconSmall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal fun RememberStateFlowScope.vaultListToolbarFlow(
    args: VaultRoute.Args,
    folderId: String?,
    showKeyboardSink: MutableStateFlow<Boolean>,
    rememberSortSink: MutableStateFlow<Boolean>,
    syncFlow: Flow<Boolean>,
    getFolders: GetFolders,
    queueSyncAll: QueueSyncAll,
    // The folder source; defaulted to the use case, but the vault-list producer
    // passes its shared (replay-1) flow so the rename action does not open a
    // second folder subscription.
    foldersFlow: Flow<List<DFolder>> = getFolders(),
    clearVaultSession: ClearVaultSession,
    onRename: (List<DFolder>) -> Unit,
): Flow<List<ContextItem>> {
    val actionArchiveItem = FlatItemAction(
        id = "vaultList.archive",
        leading = {
            Icon(Icons.Outlined.Archive, null)
        },
        title = Res.string.archive.wrap(),
        trailing = {
            ChevronIcon()
        },
        onClick = onClick {
            val newArgs = args.copy(
                appBar = VaultRoute.Args.AppBar(
                    subtitle = args.appBar?.subtitle
                        ?: TextHolder.Res(Res.string.home_vault_label),
                    title = translate(Res.string.archive),
                ),
                archive = true,
                preselect = false,
                canAddSecrets = false,
            )
            val route = VaultListRoute(newArgs)
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
    val actionArchiveFlow = flowOf(actionArchiveItem)
    val actionTrashItem = FlatItemAction(
        id = "vaultList.trash",
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
                        ?: TextHolder.Res(Res.string.home_vault_label),
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
        id = "vaultList.downloads",
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
        actionArchiveFlow,
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
                id = "vault.action.always_show_keyboard.$showKeyboard",
                leading = {
                    Icon(
                        Icons.Outlined.Keyboard,
                        null,
                    )
                },
                trailing = {
                    SwitchExpressive(
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
                // See the id note above: non-visual, carries the toggle state for
                // the native bridge only.
                id = "vault.action.remember_sorting.$rememberSorting",
                leading = {
                    Icon(
                        Icons.Outlined.SortByAlpha,
                        null,
                    )
                },
                trailing = {
                    SwitchExpressive(
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
                id = "vaultList.sync",
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
        id = "vaultList.lock",
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
    val actionFolderRenameFlow = foldersFlow
        .map { folders ->
            val folder = folders.firstOrNull { it.id == folderId }
            if (folder != null) {
                FlatItemAction(
                    id = "vaultList.renameFolder",
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
    return if (args.canAlwaysShowKeyboard) {
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

internal fun RememberStateFlowScope.vaultRenameFoldersAction(
    folders: List<DFolder>,
    confirmationRouteFactory: ConfirmationRouteFactory,
    renameFolderById: RenameFolderById,
) = action {
    val route = confirmationRouteFactory.registerRouteResultReceiver(
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
    ) { result ->
        if (result is ConfirmationResult.Confirm) {
            val folderIdsToNames = result.data
                .mapValues { it.value as String }
            renameFolderById(folderIdsToNames)
                .launchIn(appScope)
        }
    }
    val intent = NavigationIntent.NavigateToRoute(route)
    navigate(intent)
}

internal fun vaultListStructureRevision(
    filterConfig: FilterHolder?,
    queryConfig: CompiledQueryPlan?,
    orderConfig: ComparatorHolder?,
): Int = (filterConfig?.id ?: 0) xor
        (queryConfig?.id ?: 0) xor
        (orderConfig?.hashCode() ?: 0)

internal fun buildVaultComparator(
    orderConfig: ComparatorHolder,
): Comparator<VaultItem2.Item> = Comparator { aModel, bModel ->

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

internal fun trimVaultItemBadges(
    list: List<VaultItem2>,
    argsFilter: DFilter?,
    filterConfig: FilterHolder?,
    orderConfig: ComparatorHolder?,
    mode: AppMode,
): List<VaultItem2> {
    val userObservedFilterConfig = DFilter.And(
        listOfNotNull(
            argsFilter,
            filterConfig?.filter,
        ),
    )

    val keepOtp = DFilter
        .findAny<DFilter.ByOtp>(userObservedFilterConfig) != null
    val keepAttachment = DFilter
        .findAny<DFilter.ByAttachments>(userObservedFilterConfig) != null
    val keepPasskey = DFilter
        .findAny<DFilter.ByPasskeys>(userObservedFilterConfig) != null ||
            // If a user is in the pick a passkey mode,
            // then we want to always show it in the items.
            mode is AppMode.PickPasskey ||
            mode is AppMode.SavePasskey
    val keepPassword = orderConfig
        ?.let {
            // Regular password sort is not included here intentionally,
            // because if that is selected then the password will be
            // previewed as a section.
            val sort = it.comparator
            sort is PasswordLastModifiedSort ||
                    sort is PasswordStrengthSort
        } != false ||
            // If a user is in the pick a password mode,
            // then we want to always show it in the items.
            mode is AppMode.SavePassword
    return pruneVaultListItemPresentation(
        list = list,
        keepOtp = keepOtp,
        keepPasskey = keepPasskey,
        keepPassword = keepPassword,
        keepAttachment = keepAttachment,
    )
}

internal const val VAULT_PREFERRED_ID_PREFIX = "preferred."
internal const val VAULT_SECTION_ID_PREFIX = "section."
internal const val VAULT_QUICK_FILTERS_ID = "quick_filters"

/** Prefixes a cipher [id] into an autofill-suggestions ("preferred") row id. */
internal fun preferredRowId(id: String): String = VAULT_PREFERRED_ID_PREFIX + id

/** Reverse of [preferredRowId]: drops the "preferred." prefix if present. */
internal fun stripPreferredPrefix(id: String): String = id.removePrefix(VAULT_PREFERRED_ID_PREFIX)

internal suspend fun buildVaultPreferredItems(
    items: List<VaultItem2.Item>,
    autofillTarget: AutofillTarget,
    getSuggestions: GetSuggestions<Any?>,
    equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
): List<VaultItem2.Item> = getSuggestions(
    items,
    Getter {
        val item = it as VaultItem2.Item
        item.source
    },
    autofillTarget,
    equivalentDomainsBuilderFactory,
)
    .bind().let { it as List<VaultItem2.Item> }
    .map { item ->
        item.copy(
            id = preferredRowId(item.id),
        )
    }

internal suspend fun filterVaultCiphersForMode(
    ciphers: List<DSecret>,
    mode: AppMode,
    args: VaultRoute.Args,
    passkeyTargetCheck: PasskeyTargetCheck,
): List<DSecret> {
    val modeFiltered = ciphers.run {
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
    return modeFiltered.filter {
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
}

internal suspend fun filterVaultItems(
    filterContext: CipherFilterContext,
    items: List<VaultItem2.Item>,
    filter: DFilter,
): List<VaultItem2.Item> {
    val ciphers = items.map { it.source }
    val predicate = filter.prepare(filterContext, ciphers)
    return items.filter { predicate(it.source) }
}

internal suspend fun queryVaultItems(
    searchContext: VaultSearchContext,
    items: List<VaultItem2.Item>,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
): List<VaultItem2.Item> = searchContext.searchIndex.evaluate(
    plan = searchContext.queryPlan,
    candidates = items,
    highlightBackgroundColor = highlightBackgroundColor,
    highlightContentColor = highlightContentColor,
)

internal fun RememberStateFlowScope.buildVaultCreateActions(
    mode: AppMode,
    queryTrimmed: String,
): List<FlatItemAction> {
    fun createTypeAction(
        type: DSecret.Type,
    ) = FlatItemAction(
        id = "vaultList.create.${type.name}",
        leading = icon(type.iconImageVector()),
        title = type.titleH().wrap(),
        onClick = {
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
                    type = type,
                    autofill = autofill,
                    name = queryTrimmed.takeIf { it.isNotEmpty() },
                ),
            )
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )

    // You can not create a passkey at will, it
    // must be a separate action. During the pick passkey
    // request you can only select existing ones.
    if (mode is AppMode.PickPasskey) {
        return emptyList()
    }
    if (mode is AppMode.HasType) {
        val type = mode.type
        if (type != null) {
            return listOf(
                createTypeAction(
                    type = type,
                ),
            )
        }
    }
    return listOf(
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
        createTypeAction(
            type = DSecret.Type.SshKey,
        ),
        createTypeAction(
            type = DSecret.Type.GpgKey,
        ),
    )
}

internal fun RememberStateFlowScope.buildVaultCreatePaywallActions(): List<FlatItemAction> = listOf(
    FlatItemAction(
        id = "vaultList.subscriptions",
        title = TextHolder.Res(Res.string.settings_subscriptions_header_title),
        onClick = {
            val intent = NavigationIntent.NavigateToRoute(SubscriptionsSettingsRoute)
            navigate(intent)
        },
    ),
)

/** The effective create-actions list plus whether the paywall swap was applied. */
internal data class VaultEffectiveCreateActions(
    val actions: List<FlatItemAction>,
    val paywalled: Boolean,
)

internal fun RememberStateFlowScope.buildVaultEffectiveCreateActions(
    mode: AppMode,
    queryTrimmed: String,
    hasAccounts: Boolean,
    paywallOk: Boolean,
): VaultEffectiveCreateActions {
    val primaryActions = if (hasAccounts) {
        buildVaultCreateActions(
            mode = mode,
            queryTrimmed = queryTrimmed,
        )
    } else {
        emptyList()
    }
    val paywalled = !paywallOk && primaryActions.isNotEmpty()
    val actions = if (paywalled) {
        buildVaultCreatePaywallActions()
    } else {
        primaryActions
    }
    return VaultEffectiveCreateActions(
        actions = actions,
        paywalled = paywalled,
    )
}

internal fun RememberStateFlowScope.buildVaultItemModeMenu(
    mode: AppMode,
    secret: DSecret,
    copyActions: List<FlatItemAction>,
    canWrite: Boolean,
    cipherSink: EventFlow<DSecret>,
): VaultItem2.Item.Action {
    fun buildContextItemsForSaveAction(
        block: ContextItemBuilder.() -> Unit,
    ) = buildContextItems {
        section {
            block()
        }
        section {
            this += FlatItemAction(
                id = "vaultList.save.viewDetails",
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

    val dropdown = when (mode) {
        is AppMode.Pick -> buildContextItems {
            section {
                this += FlatItemAction(
                    id = "vaultList.pick.autofill",
                    icon = Icons.Outlined.AutoAwesome,
                    title = Res.string.autofill.wrap(),
                    onClick = {
                        val extra = AppMode.Pick.Extra()
                        mode.onAutofill(secret, extra)
                    },
                )
                if (canWrite) {
                    this += FlatItemAction(
                        id = "vaultList.pick.autofillAndSave",
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
                copyActions.forEach { action ->
                    this += action
                }
            }
            section {
                this += FlatItemAction(
                    id = "vaultList.pick.viewDetails",
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

        is AppMode.Save -> buildContextItemsForSaveAction {
            this += FlatItemAction(
                id = "vaultList.save.saveTo",
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

        is AppMode.SavePasskey -> buildContextItemsForSaveAction {
            this += FlatItemAction(
                id = "vaultList.savePasskey.saveTo",
                icon = Icons.Outlined.Save,
                title = Res.string.ciphers_save_to.wrap(),
                text = Res.string.ciphers_save_to_adds_credentials_passkey.wrap(),
                onClick = {
                    mode.onComplete(secret)
                },
            )
        }

        is AppMode.SavePassword -> buildContextItemsForSaveAction {
            this += FlatItemAction(
                id = "vaultList.savePassword.saveTo",
                icon = Icons.Outlined.Save,
                title = Res.string.ciphers_save_to.wrap(),
                text = Res.string.ciphers_save_to_replaces_credentials_username_password.wrap(),
                onClick = {
                    mode.onComplete(secret)
                },
            )
        }

        is AppMode.PickPasskey ->
            return VaultItem2.Item.Action.Go(
                onClick = { cipherSink.emit(secret) },
            )

        is AppMode.Main ->
            return VaultItem2.Item.Action.Go(
                onClick = { cipherSink.emit(secret) },
            )

        is AppMode.QuickSearch ->
            return VaultItem2.Item.Action.None
    }
    return VaultItem2.Item.Action.Dropdown(
        actions = dropdown,
    )
}

internal class VaultItemBadgeTapActions(
    val onClickAttachment: suspend (DSecret.Attachment) -> (() -> Unit)?,
    val onClickPasskey: suspend (DSecret.Login.Fido2Credentials) -> (() -> Unit)?,
    val onClickPassword: suspend (DSecret.Login) -> (() -> Unit)?,
)

internal fun RememberStateFlowScope.buildVaultBadgeTapActions(
    mode: AppMode,
    secret: DSecret,
    passkeyTargetCheck: PasskeyTargetCheck,
    passkeysCredentialViewRouteFactory: PasskeysCredentialViewRouteFactory,
): VaultItemBadgeTapActions = VaultItemBadgeTapActions(
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
        }
    },
    onClickPassword = { credential ->
        // lambda
        {
            val password = credential.password
                .orEmpty()
            val route = LargeTypeRoute(
                args = LargeTypeRoute.Args(
                    phrases = listOf(password),
                    colorize = true,
                ),
            )
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        }
    },
)
