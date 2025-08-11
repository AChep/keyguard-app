package com.artemchep.keyguard.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DMeta
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.PutProfileHiddenRequest
import com.artemchep.keyguard.common.model.firstOrNull
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.common.usecase.GetFingerprint
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetGravatarUrl
import com.artemchep.keyguard.common.usecase.GetMetas
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetSends
import com.artemchep.keyguard.common.usecase.PutAccountColorById
import com.artemchep.keyguard.common.usecase.PutAccountMasterPasswordHintById
import com.artemchep.keyguard.common.usecase.PutAccountNameById
import com.artemchep.keyguard.common.usecase.PutProfileHidden
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.RemoveAccountById
import com.artemchep.keyguard.common.usecase.SupervisorRead
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.feature.auth.login.LoginRoute
import com.artemchep.keyguard.feature.colorpicker.ColorPickerRoute
import com.artemchep.keyguard.feature.colorpicker.createColorPickerDialogIntent
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import com.artemchep.keyguard.feature.emailleak.EmailLeakRoute
import com.artemchep.keyguard.feature.equivalentdomains.EquivalentDomainsRoute
import com.artemchep.keyguard.feature.export.ExportRoute
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsRoute
import com.artemchep.keyguard.feature.home.vault.folders.FoldersRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.feature.home.vault.model.transformShapes
import com.artemchep.keyguard.feature.home.vault.organizations.OrganizationsRoute
import com.artemchep.keyguard.feature.largetype.LargeTypeRoute
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.send.SendRoute
import com.artemchep.keyguard.feature.watchtower.WatchtowerRoute
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AnimatedTotalCounterBadge
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.autoclose.launchAutoPopSelfHandler
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.EmailIcon
import com.artemchep.keyguard.ui.icons.KeyguardCipher
import com.artemchep.keyguard.ui.icons.KeyguardCollection
import com.artemchep.keyguard.ui.icons.KeyguardOrganization
import com.artemchep.keyguard.ui.icons.KeyguardPremium
import com.artemchep.keyguard.ui.icons.SyncIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.icons.iconSmall
import com.artemchep.keyguard.ui.theme.isDark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun accountState(
    accountId: AccountId,
): AccountViewState = with(localDI().direct) {
    accountState(
        queueSyncById = instance(),
        syncSupervisor = instance(),
        getGravatarUrl = instance(),
        clipboardService = instance(),
        dateFormatter = instance(),
        removeAccountById = instance(),
        getFingerprint = instance(),
        putAccountNameById = instance(),
        putAccountColorById = instance(),
        putAccountMasterPasswordHintById = instance(),
        putProfileHidden = instance(),
        getAccounts = instance(),
        getProfiles = instance(),
        getCiphers = instance(),
        getSends = instance(),
        getEquivalentDomains = instance(),
        getFolders = instance(),
        getCollections = instance(),
        getOrganizations = instance(),
        getMetas = instance(),
        db = instance(),
        accountId = accountId,
    )
}

private data class AccountCounters(
    val ciphers: Int,
    val sends: Int,
    val organizations: Int,
    val collections: Int,
    val equivalentDomains: Int,
    val folders: Int,
)

@Composable
fun accountState(
    queueSyncById: QueueSyncById,
    syncSupervisor: SupervisorRead,
    getGravatarUrl: GetGravatarUrl,
    clipboardService: ClipboardService,
    dateFormatter: DateFormatter,
    removeAccountById: RemoveAccountById,
    getFingerprint: GetFingerprint,
    putAccountNameById: PutAccountNameById,
    putAccountColorById: PutAccountColorById,
    putAccountMasterPasswordHintById: PutAccountMasterPasswordHintById,
    putProfileHidden: PutProfileHidden,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getCiphers: GetCiphers,
    getSends: GetSends,
    getEquivalentDomains: GetEquivalentDomains,
    getFolders: GetFolders,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
    getMetas: GetMetas,
    db: DatabaseManager,
    accountId: AccountId,
): AccountViewState = produceScreenState(
    initial = AccountViewState(),
    key = "account:$accountId",
    args = arrayOf(
        accountId,
    ),
) {
    fun doReLogin(
        accountId: AccountId,
        email: String,
        env: ServerEnv,
    ) {
        val route = registerRouteResultReceiver(
            route = LoginRoute(
                args = LoginRoute.Args(
                    accountId = accountId.id,
                    email = email,
                    emailEditable = false,
                    env = env,
                    envEditable = false,
                ),
            ),
        ) {
            navigate(NavigationIntent.Pop)
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    fun doSyncAccountById(accountId: AccountId) {
        queueSyncById(accountId)
            .launchIn(appScope)
    }

    suspend fun doRemoveAccountById(accountId: AccountId) {
        val intent = createConfirmationDialogIntent(
            icon = icon(Icons.AutoMirrored.Outlined.Logout),
            title = translate(Res.string.account_log_out_confirmation_title),
            message = translate(Res.string.account_log_out_confirmation_text),
        ) {
            removeAccountById(setOf(accountId))
                .launchIn(appScope)
        }
        navigate(intent)
    }

    fun doHideProfileById(profileId: String, hidden: Boolean) {
        val request = PutProfileHiddenRequest(
            patch = mapOf(
                profileId to hidden,
            ),
        )
        putProfileHidden(request)
            .launchIn(appScope)
    }

    val accountFlow = getAccounts()
        .map { it.firstOrNull(accountId) }
    launchAutoPopSelfHandler(accountFlow)
    val metaFlow = getMetas()
        .map { it.firstOrNull(accountId) }
    val profileFlow = getProfiles()
        .map { it.firstOrNull(accountId) }

    val countersFlow = kotlin.run {
        fun <T> Flow<List<T>>.mapCount(predicate: (T) -> Boolean) = this
            .map { list -> list.count(predicate) }
            .distinctUntilChanged()

        val ciphersCountFlow = getCiphers()
            .mapCount {
                it.accountId == accountId.id &&
                        it.deletedDate == null
            }
        val sendsCountFlow = getSends()
            .mapCount {
                it.accountId == accountId.id
            }
        val equivalentDomainsCountFlow = getEquivalentDomains()
            .mapCount {
                it.accountId == accountId.id &&
                        !it.excluded
            }
        val foldersCountFlow = getFolders()
            .mapCount {
                it.accountId == accountId.id &&
                        !it.deleted
            }
        val collectionsCountFlow = getCollections()
            .mapCount { it.accountId == accountId.id }
        val organizationsCountFlow = getOrganizations()
            .mapCount { it.accountId == accountId.id }

        combine(
            ciphersCountFlow,
            sendsCountFlow,
            organizationsCountFlow,
            collectionsCountFlow,
            equivalentDomainsCountFlow,
            foldersCountFlow,
        ) { counts ->
            val ciphersCount = counts[0]
            val sendsCount = counts[1]
            val organizationsCount = counts[2]
            val collectionsCount = counts[3]
            val equivalentDomainsCount = counts[4]
            val foldersCount = counts[5]
            AccountCounters(
                ciphers = ciphersCount,
                sends = sendsCount,
                organizations = organizationsCount,
                collections = collectionsCount,
                equivalentDomains = equivalentDomainsCount,
                folders = foldersCount,
            )
        }
    }

    val copyText = copy(clipboardService)

    fun <T : Any> Flow<T?>.prepare() = this
        .distinctUntilChanged()

    fun <T : Any> Flow<T>.prepare() = this
        .distinctUntilChanged()

    val primaryActionFlow = metaFlow.prepare()
        .map { meta ->
            val requiresAuthentication = meta?.lastSyncResult is DMeta.LastSyncResult.Failure
            // TODO:
//                    &&
//                    meta.lastSyncResult.requiresAuthentication
            val primaryActionOrNull = if (requiresAuthentication) {
                val token = db.get()
                    .effectMap {
                        val info = it
                            .accountQueries
                            .getByAccountId(accountId.id)
                            .executeAsOne()
                            .data_
                        info
                    }
                    .attempt()
                    .bind()
                token.fold(
                    ifLeft = {
                        null
                    },
                    ifRight = { t ->
                        val email = t.user.email
                        val env = t.env.back()
                        AccountViewState.Content.Data.PrimaryAction(
                            text = translate(Res.string.account_action_sign_in_title),
                            icon = Icons.AutoMirrored.Outlined.Login,
                            onClick = ::doReLogin
                                .partially1(accountId)
                                .partially1(email)
                                .partially1(env),
                        )
                    },
                )
            } else {
                null
            }
            primaryActionOrNull
        }
    val itemsFlow = combine(
        metaFlow.prepare(),
        accountFlow.prepare(),
        profileFlow.prepare(),
        countersFlow.prepare(),
    ) { meta, account, profile, counters ->
        val items = buildItemsFlow(
            accountId = accountId,
            scope = this,
            account = account,
            profile = profile,
            meta = meta,
            counters = counters,
            copyText = copyText,
            putAccountNameById = putAccountNameById,
            putAccountColorById = putAccountColorById,
            putAccountMasterPasswordHintById = putAccountMasterPasswordHintById,
            queueSyncById = queueSyncById,
            getFingerprint = getFingerprint,
            dateFormatter = dateFormatter,
            getGravatarUrl = getGravatarUrl,
        ).toList()
        items
            .transformShapes()
    }
    val actionsFlow = combine(
        accountFlow,
        profileFlow,
    ) { accountOrNull, profileOrNull ->
        val syncing = false
        val busy = false
        buildContextItems {
            section {
                if (accountOrNull == null) {
                    return@section
                }

                this += FlatItemAction(
                    leading = {
                        SyncIcon(rotating = syncing)
                    },
                    title = Res.string.sync.wrap(),
                    onClick = if (busy) {
                        null
                    } else {
                        // on click listener
                        ::doSyncAccountById.partially1(accountOrNull.id)
                    },
                )
                this += ExportRoute.actionOrNull(
                    translator = this@produceScreenState,
                    accountId = accountId,
                    individual = true,
                    navigate = ::navigate,
                )
                this += ExportRoute.actionOrNull(
                    translator = this@produceScreenState,
                    accountId = accountId,
                    individual = false,
                    navigate = ::navigate,
                )
            }
            section {
                if (profileOrNull == null) {
                    return@section
                }

                val hidden = profileOrNull.hidden

                val onCheckedChange = ::doHideProfileById
                    .partially1(profileOrNull.profileId)
                this += FlatItemAction(
                    leading = {
                        Icon(
                            Icons.Outlined.VisibilityOff,
                            null,
                        )
                    },
                    trailing = {
                        Switch(
                            checked = hidden,
                            onCheckedChange = onCheckedChange,
                        )
                    },
                    title = Res.string.account_action_hide_title.wrap(),
                    text = Res.string.account_action_hide_text.wrap(),
                    onClick = onCheckedChange.partially1(!hidden),
                )
            }
            section {
                if (accountOrNull == null) {
                    return@section
                }

                this += FlatItemAction(
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    title = Res.string.account_action_sign_out_title.wrap(),
                    onClick = if (busy) {
                        null
                    } else {
                        // on click listener
                        onClick {
                            doRemoveAccountById(accountId)
                        }
                    },
                )
            }
        }
    }
    combine(
        accountFlow,
        actionsFlow,
        itemsFlow,
        primaryActionFlow,
    ) { accountOrNull, actions, items, primaryAction ->
        if (accountOrNull == null) {
            return@combine AccountViewState.Content.NotFound
        }
        AccountViewState.Content.Data(
            data = accountOrNull,
            actions = actions,
            items = items,
            primaryAction = primaryAction,
            onOpenWebVault = {
                val intent = NavigationIntent.NavigateToBrowser(accountOrNull.url)
                navigate(intent)
            },
        )
    }
        .map { content ->
            AccountViewState(
                content = content,
            )
        }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun buildItemsFlow(
    accountId: AccountId,
    scope: RememberStateFlowScope,
    account: DAccount?,
    profile: DProfile?,
    meta: DMeta?,
    counters: AccountCounters,
    getGravatarUrl: GetGravatarUrl,
    copyText: CopyText,
    putAccountNameById: PutAccountNameById,
    putAccountColorById: PutAccountColorById,
    putAccountMasterPasswordHintById: PutAccountMasterPasswordHintById,
    queueSyncById: QueueSyncById,
    getFingerprint: GetFingerprint,
    dateFormatter: DateFormatter,
): Flow<VaultViewItem> = flow {
    if (meta != null) {
        when (val syncResult = meta.lastSyncResult) {
            is DMeta.LastSyncResult.Success -> {
                // Show nothing.
            }

            is DMeta.LastSyncResult.Failure -> {
                val time = dateFormatter.formatDateTime(syncResult.timestamp)
                val model = VaultViewItem.Error(
                    id = "error",
                    name = "Couldn't sync the account",
                    message = syncResult.reason ?: "Something went wrong",
                    timestamp = time,
                    onRetry = if (true) {
                        // lambda
                        {
                            queueSyncById(accountId)
                                .launchIn(scope.appScope)
                        }
                    } else {
                        null
                    },
                )
                emit(model)
            }

            null -> {
                // Do nothing.
            }
        }
    }
    if (profile != null) {
        emitName(
            scope = scope,
            profile = profile,
            copyText = copyText,
            putAccountNameById = putAccountNameById,
            putAccountColorById = putAccountColorById,
        )
        emitEmail(
            scope = scope,
            profile = profile,
            getGravatarUrl = getGravatarUrl,
            copyText = copyText,
        )
    }
    if (account != null && profile != null && !profile.twoFactorEnabled) {
        val inactiveTfaItem = VaultViewItem.InactiveTotp(
            id = "inactive_tfa",
            chevron = true,
            onClick = {
                val intent = NavigationIntent.NavigateToBrowser(account.url)
                scope.navigate(intent)
            },
        )
        emit(inactiveTfaItem)
    }
    if (meta != null) {
        val syncTimestamp = meta.lastSyncTimestamp
        if (syncTimestamp != null) {
            val syncDate = dateFormatter.formatDateTime(syncTimestamp)
            val syncInfoText = scope.translate(Res.string.account_last_synced_at, syncDate)
            val syncInfoItem = VaultViewItem.Label(
                id = "sync.timestamp",
                text = AnnotatedString(syncInfoText),
            )
            emit(syncInfoItem)
        }
    }

    val quickActions = VaultViewItem.QuickActions(
        id = "quick_actions",
        actions = buildContextItems {
            section {
                this += ExportRoute.actionOrNull(
                    translator = scope,
                    accountId = accountId,
                    individual = true,
                    navigate = scope::navigate,
                )
                this += ExportRoute.actionOrNull(
                    translator = scope,
                    accountId = accountId,
                    individual = false,
                    navigate = scope::navigate,
                )
            }
        },
    )
    emit(quickActions)

    if (account != null) {
        val ff0 = VaultViewItem.Action(
            id = "ciphers",
            title = scope.translate(Res.string.items),
            leading = {
                BadgedBox(
                    badge = {
                        AnimatedTotalCounterBadge(
                            count = counters.ciphers,
                        )
                    },
                ) {
                    Icon(Icons.Outlined.KeyguardCipher, null)
                }
            },
            trailing = {
                ChevronIcon()
            },
            onClick = {
                val route = VaultRoute.by(account = account)
                val intent = NavigationIntent.NavigateToRoute(route)
                scope.navigate(intent)
            },
        )
        emit(ff0)
        val sendsItem = VaultViewItem.Action(
            id = "sends",
            title = scope.translate(Res.string.sends),
            leading = {
                BadgedBox(
                    badge = {
                        val count = counters.sends
                        AnimatedTotalCounterBadge(
                            count = count,
                        )
                    },
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, null)
                }
            },
            trailing = {
                ChevronIcon()
            },
            onClick = {
                val route = SendRoute.by(account = account)
                val intent = NavigationIntent.NavigateToRoute(route)
                scope.navigate(intent)
            },
        )
        emit(sendsItem)
    }
    val ff = VaultViewItem.Action(
        id = "folders",
        title = scope.translate(Res.string.folders),
        leading = {
            BadgedBox(
                badge = {
                    val count = counters.folders
                    AnimatedTotalCounterBadge(
                        count = count,
                    )
                },
            ) {
                Icon(Icons.Outlined.Folder, null)
            }
        },
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val route = FoldersRoute(
                args = FoldersRoute.Args(
                    filter = DFilter.ById(accountId.id, DFilter.ById.What.ACCOUNT),
                ),
            )
            val intent = NavigationIntent.NavigateToRoute(route)
            scope.navigate(intent)
        },
    )
    emit(ff)
    val ff2 = VaultViewItem.Action(
        id = "collections",
        title = scope.translate(Res.string.collections),
        leading = {
            BadgedBox(
                badge = {
                    val count = counters.collections
                    AnimatedTotalCounterBadge(
                        count = count,
                    )
                },
            ) {
                Icon(Icons.Outlined.KeyguardCollection, null)
            }
        },
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val route = CollectionsRoute(
                args = CollectionsRoute.Args(
                    accountId = accountId,
                    organizationId = null,
                ),
            )
            val intent = NavigationIntent.NavigateToRoute(route)
            scope.navigate(intent)
        },
    )
    emit(ff2)
    val ff3 = VaultViewItem.Action(
        id = "organizations",
        title = scope.translate(Res.string.organizations),
        leading = {
            BadgedBox(
                badge = {
                    val count = counters.organizations
                    AnimatedTotalCounterBadge(
                        count = count,
                    )
                },
            ) {
                Icon(Icons.Outlined.KeyguardOrganization, null)
            }
        },
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val route = OrganizationsRoute(
                args = OrganizationsRoute.Args(
                    accountId = accountId,
                ),
            )
            val intent = NavigationIntent.NavigateToRoute(route)
            scope.navigate(intent)
        },
    )
    emit(ff3)
    val watchtowerSectionItem = VaultViewItem.Section(
        id = "watchtower.section",
    )
    emit(watchtowerSectionItem)
    val watchtowerItem = VaultViewItem.Action(
        id = "watchtower",
        title = scope.translate(Res.string.watchtower_header_title),
        leading = {
            Icon(Icons.Outlined.Security, null)
        },
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val route = WatchtowerRoute(
                args = WatchtowerRoute.Args(
                    filter = DFilter.ById(
                        id = accountId.id,
                        what = DFilter.ById.What.ACCOUNT,
                    ),
                ),
            )
            val intent = NavigationIntent.NavigateToRoute(route)
            scope.navigate(intent)
        },
    )
    emit(watchtowerItem)
    val ff4 = VaultViewItem.Section(
        id = "section",
        text = scope.translate(Res.string.security),
    )
    emit(ff4)
    if (profile != null) {
        emitFingerprint(
            scope = scope,
            profile = profile,
            copyText = copyText,
            getFingerprint = getFingerprint,
        )
        emitMasterPasswordHint(
            scope = scope,
            profile = profile,
            copyText = copyText,
            putAccountMasterPasswordHintById = putAccountMasterPasswordHintById,
        )
        val ff5 = VaultViewItem.Section(
            id = "section2",
            text = scope.translate(Res.string.info),
        )
        emit(ff5)
    }

    if (account != null && profile != null) {
        emitPremium(
            scope = scope,
            account = account,
            profile = profile,
        )
        val unofficialServer = profile.unofficialServer
        if (unofficialServer) {
            val unofficialServerText = scope.translate(Res.string.bitwarden_unofficial_server)
            val unofficialServerItem = VaultViewItem.Label(
                id = "unofficial_server",
                text = AnnotatedString(unofficialServerText),
            )
            emit(unofficialServerItem)
        }
//        emitMasterPasswordHint(
//            profile = profile,
//            copyText = copyText,
//        )
    }

    val equivalentDomainsSectionItem = VaultViewItem.Section(
        id = "equivalent_domains.section",
    )
    emit(equivalentDomainsSectionItem)
    val equivalentDomainsItem = VaultViewItem.Action(
        id = "equivalent_domains",
        title = scope.translate(Res.string.equivalent_domains),
        leading = {
            BadgedBox(
                badge = {
                    val count = counters.equivalentDomains
                    AnimatedTotalCounterBadge(
                        count = count,
                    )
                },
            ) {
                Icon(Icons.Outlined.Domain, null)
            }
        },
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val route = EquivalentDomainsRoute(
                args = EquivalentDomainsRoute.Args(
                    accountId = accountId,
                ),
            )
            val intent = NavigationIntent.NavigateToRoute(route)
            scope.navigate(intent)
        },
    )
    emit(equivalentDomainsItem)
}

private suspend fun FlowCollector<VaultViewItem>.emitName(
    scope: RememberStateFlowScope,
    profile: DProfile,
    copyText: CopyText,
    putAccountNameById: PutAccountNameById,
    putAccountColorById: PutAccountColorById,
) {
    val name = profile.name

    suspend fun onClick() {
        val intent = scope.createConfirmationDialogIntent(
            icon = icon(Icons.Outlined.Edit),
            item = ConfirmationRoute.Args.Item.StringItem(
                key = "name",
                title = scope.translate(Res.string.account_name),
                value = name,
                // You can have an empty account name.
                canBeEmpty = true,
            ),
            title = scope.translate(Res.string.account_action_change_name_title),
        ) { name ->
            val profileIdsToNames = mapOf(
                AccountId(profile.accountId) to name,
            )
            putAccountNameById(profileIdsToNames)
                .launchIn(scope.appScope)
        }
        scope.navigate(intent)
    }

    fun onEditColor() {
        val intent = scope.createColorPickerDialogIntent(
            args = ColorPickerRoute.Args(
                color = profile.accentColor.dark,
            ),
        ) { color ->
            val profileIdsToColors = mapOf(
                AccountId(profile.accountId) to color,
            )
            putAccountColorById(profileIdsToColors)
                .launchIn(scope.appScope)
        }
        scope.navigate(intent)
    }

    val id = "name"
    val nameEditActionItem = FlatItemAction(
        icon = Icons.Outlined.Edit,
        title = Res.string.account_action_change_name_title.wrap(),
        onClick = scope.onClick {
            onClick()
        },
    )
    val colorEditActionItem = FlatItemAction(
        leading = iconSmall(Icons.Outlined.Edit, Icons.Outlined.ColorLens),
        trailing = {
            val accentColor = if (MaterialTheme.colorScheme.isDark) {
                profile.accentColor.dark
            } else {
                profile.accentColor.light
            }
            Box(
                Modifier
                    .background(accentColor, CircleShape)
                    .size(24.dp),
            )
        },
        title = Res.string.account_action_change_color_title.wrap(),
        onClick = ::onEditColor,
    )
    val leading: @Composable RowScope.() -> Unit = {
        val backgroundColor = if (MaterialTheme.colorScheme.isDark) {
            profile.accentColor.dark
        } else {
            profile.accentColor.light
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(backgroundColor),
        )
    }
    val nameItem = if (name.isBlank()) {
        VaultViewItem.Value(
            id = id,
            elevation = 1.dp,
            title = scope.translate(Res.string.account_name),
            value = "",
            leading = leading,
            dropdown = listOfNotNull(
                // action to edit current name
                nameEditActionItem,
                colorEditActionItem,
            ),
        )
    } else {
        VaultViewItem.Value(
            id = id,
            elevation = 1.dp,
            title = scope.translate(Res.string.account_name),
            value = name,
            leading = leading,
            dropdown = buildContextItems {
                section {
                    this += copyText.FlatItemAction(
                        title = Res.string.copy.wrap(),
                        value = name,
                    )
                }
                section {
                    this += LargeTypeRoute.showInLargeTypeActionOrNull(
                        translator = scope,
                        text = name,
                        navigate = scope::navigate,
                    )
                    this += LargeTypeRoute.showInLargeTypeActionAndLockOrNull(
                        translator = scope,
                        text = name,
                        navigate = scope::navigate,
                    )
                }
                section {
                    this += nameEditActionItem
                    this += colorEditActionItem
                }
            },
        )
    }
    emit(nameItem)
}

private suspend fun FlowCollector<VaultViewItem>.emitEmail(
    scope: RememberStateFlowScope,
    profile: DProfile,
    getGravatarUrl: GetGravatarUrl,
    copyText: CopyText,
) {
    val id = "email"
    val email = profile.email
    if (email.isNotBlank()) {
        val gravatarUrl = getGravatarUrl(email)
            .attempt()
            .bind()
            .getOrNull()
        val badges = kotlin.run {
            val list = mutableListOf<StateFlow<VaultViewItem.Value.Badge>>()
            // Account email verification badge
            list += if (profile.emailVerified) {
                VaultViewItem.Value.Badge(
                    text = scope.translate(Res.string.email_verified),
                    score = 1f,
                )
            } else {
                VaultViewItem.Value.Badge(
                    text = scope.translate(Res.string.email_not_verified),
                    score = 0.5f,
                )
            }.let(::MutableStateFlow)
            // Account two-factor authentication badge
            if (profile.twoFactorEnabled) {
                list += VaultViewItem.Value.Badge(
                    text = scope.translate(Res.string.account_action_tfa_title),
                    score = 1f,
                ).let(::MutableStateFlow)
            }
            list
        }
        val emailItem = VaultViewItem.Value(
            id = id,
            elevation = 1.dp,
            title = scope.translate(Res.string.email),
            value = email,
            leading = {
                EmailIcon(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape),
                    gravatarUrl = gravatarUrl,
                )
            },
            dropdown = buildContextItems {
                section {
                    this += copyText.FlatItemAction(
                        title = Res.string.copy.wrap(),
                        value = email,
                        type = CopyText.Type.EMAIL,
                    )
                }
                section {
                    this += LargeTypeRoute.showInLargeTypeActionOrNull(
                        translator = scope,
                        text = email,
                        navigate = scope::navigate,
                    )
                    this += LargeTypeRoute.showInLargeTypeActionAndLockOrNull(
                        translator = scope,
                        text = email,
                        navigate = scope::navigate,
                    )
                }
                if (!profile.emailVerified) {
                    section {
                        this += FlatItemAction(
                            leading = icon(Icons.Outlined.VerifiedUser),
                            title = Res.string.account_action_email_verify_instructions_title.wrap(),
                            onClick = null,
                        )
                    }
                }
                section {
                    this += EmailLeakRoute.checkBreachesEmailActionOrNull(
                        translator = scope,
                        accountId = AccountId(profile.accountId),
                        email = profile.email,
                        navigate = scope::navigate,
                    )
                }
            },
            badge2 = badges,
        )
        emit(emailItem)
    }
}

private suspend fun FlowCollector<VaultViewItem>.emitPremium(
    scope: RememberStateFlowScope,
    account: DAccount,
    profile: DProfile,
) {
    val id = "premium"
    val premiumItem = if (profile.premium) {
        VaultViewItem.Action(
            id = id,
            title = scope.translate(Res.string.bitwarden_premium),
            leading = {
                Icon(Icons.Outlined.KeyguardPremium, null)
            },
            trailing = {
                ChevronIcon()
            },
            badge = VaultViewItem.Action.Badge(
                text = scope.translate(Res.string.pref_item_premium_status_active),
                score = 1f,
            ),
            onClick = {
                val intent = NavigationIntent.NavigateToBrowser(account.url)
                scope.navigate(intent)
            },
        )
    } else {
        VaultViewItem.Action(
            id = id,
            title = scope.translate(Res.string.bitwarden_premium),
            text = scope.translate(Res.string.account_action_premium_purchase_instructions_title),
            leading = {
                Icon(Icons.Outlined.KeyguardPremium, null)
            },
            trailing = {
                ChevronIcon()
            },
            onClick = {
                val intent = NavigationIntent.NavigateToBrowser(account.url)
                scope.navigate(intent)
            },
        )
    }
    emit(premiumItem)
}

private suspend fun FlowCollector<VaultViewItem>.emitMasterPasswordHint(
    scope: RememberStateFlowScope,
    profile: DProfile,
    copyText: CopyText,
    putAccountMasterPasswordHintById: PutAccountMasterPasswordHintById,
) {
    val hint = profile.masterPasswordHint

    suspend fun onClick() {
        val intent = scope.createConfirmationDialogIntent(
            icon = icon(Icons.Outlined.Edit),
            item = ConfirmationRoute.Args.Item.StringItem(
                key = "name",
                title = scope.translate(Res.string.master_password_hint),
                value = hint.orEmpty(),
                // You can have an empty password hint.
                canBeEmpty = true,
            ),
            title = scope.translate(Res.string.account_action_change_master_password_hint_title),
        ) { newHint ->
            val profileIdsToNames = mapOf(
                AccountId(profile.accountId) to newHint,
            )
            putAccountMasterPasswordHintById(profileIdsToNames)
                .launchIn(scope.appScope)
        }
        scope.navigate(intent)
    }

    val id = "master_password_hint"
    val hintEditActionItem = FlatItemAction(
        icon = Icons.Outlined.Edit,
        title = Res.string.account_action_change_master_password_hint_title.wrap(),
        onClick = scope.onClick {
            onClick()
        },
    )
    val hintItem =
        VaultViewItem.Value(
            id = id,
            title = scope.translate(Res.string.master_password_hint),
            value = hint.orEmpty(),
            visibility = Visibility(
                concealed = true,
            ),
            leading = {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, null)
            },
            dropdown = buildContextItems {
                if (!hint.isNullOrBlank()) {
                    section {
                        this += copyText.FlatItemAction(
                            title = Res.string.copy.wrap(),
                            value = hint,
                        )
                    }
                }
                section {
                    this += hintEditActionItem
                }
            },
        )
    emit(hintItem)
}

private suspend fun FlowCollector<VaultViewItem>.emitFingerprint(
    scope: RememberStateFlowScope,
    profile: DProfile,
    copyText: CopyText,
    getFingerprint: GetFingerprint,
) {
    val fingerprintOrException = getFingerprint(AccountId(profile.accountId))
        .toIO()
        .crashlyticsTap()
        .attempt()
        .bind()
    val fingerprint = fingerprintOrException.getOrNull().orEmpty()
    val id = "fingerprint"
    if (fingerprint.isNotBlank()) {
        val item = VaultViewItem.Value(
            id = id,
            title = scope.translate(Res.string.fingerprint_phrase),
            value = fingerprint,
            monospace = true,
            colorize = true,
            leading = {
                Icon(Icons.Outlined.Fingerprint, null)
            },
            dropdown = buildContextItems {
                section {
                    this += copyText.FlatItemAction(
                        title = Res.string.copy.wrap(),
                        value = fingerprint,
                    )
                }
                section {
                    this += LargeTypeRoute.showInLargeTypeActionOrNull(
                        translator = scope,
                        text = fingerprint,
                        colorize = true,
                        navigate = scope::navigate,
                    )
                    this += LargeTypeRoute.showInLargeTypeActionAndLockOrNull(
                        translator = scope,
                        text = fingerprint,
                        colorize = true,
                        navigate = scope::navigate,
                    )
                }
                section {
                    this += FlatItemAction(
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        title = Res.string.fingerprint_phrase_help_title.wrap(),
                        trailing = {
                            ChevronIcon()
                        },
                        onClick = {
                            val url = "https://bitwarden.com/help/fingerprint-phrase/"
                            val intent = NavigationIntent.NavigateToBrowser(url)
                            scope.navigate(intent)
                        },
                    )
                }
            },
        )
        emit(item)
    }
}
