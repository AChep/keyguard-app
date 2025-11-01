package com.artemchep.keyguard.feature.home.settings.accounts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FilePresent
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.displayName
import com.artemchep.keyguard.common.model.firstOrNull
import com.artemchep.keyguard.common.usecase.GetAccountHasError
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCanAddAccount
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.RemoveAccountById
import com.artemchep.keyguard.common.usecase.SupervisorRead
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.common.util.flow.foldAsList
import com.artemchep.keyguard.feature.auth.AccountViewRoute
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountItem
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.short
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.BetaBadge
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.SyncSupervisorImpl
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.KeyguardCipher
import com.artemchep.keyguard.ui.icons.generateAccentColorsByAccountId
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.selection.selectionHandle
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun accountListScreenState(
    rootRouterName: String,
): AccountListStateWrapper = with(localDI().direct) {
    accountListScreenState(
        rootRouterName = rootRouterName,
        queueSyncById = instance(),
        syncSupervisor = instance(),
        removeAccountById = instance(),
        getAccounts = instance(),
        getProfiles = instance(),
        getAccountHasError = instance(),
        getCanAddAccount = instance(),
        windowCoroutineScope = instance(),
    )
}

@Composable
fun accountListScreenState(
    rootRouterName: String,
    queueSyncById: QueueSyncById,
    syncSupervisor: SupervisorRead,
    removeAccountById: RemoveAccountById,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getAccountHasError: GetAccountHasError,
    getCanAddAccount: GetCanAddAccount,
    windowCoroutineScope: WindowCoroutineScope,
): AccountListStateWrapper = produceScreenState(
    key = "account_list",
    initial = AccountListStateWrapper {
        AccountListState()
    },
    args = arrayOf(
        queueSyncById,
        syncSupervisor,
        removeAccountById,
        getAccounts,
        getCanAddAccount,
    ),
) {
    val selectionHandle = selectionHandle("selection")
    val selectedAccountsFlow = getAccounts()
        .combine(selectionHandle.idsFlow) { accounts, selectedAccountIds ->
            selectedAccountIds
                .mapNotNull { selectedAccountId ->
                    val account = accounts
                        .firstOrNull { it.id.id == selectedAccountId }
                        ?: return@mapNotNull null
                    selectedAccountId to account
                }
                .toMap()
        }
        .distinctUntilChanged()
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)

    val selectionModeFlow = selectionHandle
        .idsFlow
        .map { accountIds -> accountIds.isNotEmpty() }
        .distinctUntilChanged()

    val accountIdsSyncFlow = syncSupervisor
        .get(AccountTask.SYNC)
        .map { accounts ->
            accounts.map { it.id }.toSet()
        }

    /**
     * Returns a binary flow that determines whether the
     * account is being sync-ed at this moment or not.
     */
    fun getIsAccountSyncingFlow(
        accountId: AccountId,
    ) = accountIdsSyncFlow.map { accountId.id in it }

    val accountIdsRemoveSupervisor = SyncSupervisorImpl<String>()

    /**
     * Returns a binary flow that determines whether the
     * account is being removed at this moment or not.
     */
    fun getIsAccountRemovingFlow(
        accountId: AccountId,
    ) = accountIdsRemoveSupervisor.output.map { accountId.id in it }

    fun doSyncAccountById(accountId: AccountId) {
        queueSyncById(accountId).launchIn(windowCoroutineScope)
    }

    suspend fun onDelete(
        accountIds: Set<AccountId>,
    ) {
        val intent = createConfirmationDialogIntent(
            icon = icon(Icons.AutoMirrored.Outlined.Logout),
            title = translate(Res.string.account_log_out_confirmation_title),
            message = translate(Res.string.account_log_out_confirmation_text),
        ) {
            removeAccountById(accountIds)
                .launchIn(windowCoroutineScope)
        }
        navigate(intent)
    }

    fun onSync(
        accountIds: Set<AccountId>,
    ) {
        accountIds.forEach { accountId ->
            doSyncAccountById(accountId)
        }
    }

    val selectionFlow = combine(
        getAccounts()
            .map { accounts ->
                accounts
                    .map { it.id }
                    .toSet()
            }
            .distinctUntilChanged(),
        selectedAccountsFlow,
    ) { accountIds, selectedAccounts ->
        if (selectedAccounts.isEmpty()) {
            return@combine null
        }

        val selectedAccountIds = selectedAccounts.keys
        val f = selectedAccountIds
            .map { AccountId(it) }
            .toSet()
        val actions = buildContextItems {
            section {
                // An option to view all the items that belong
                // to these folders.
                this += FlatItemAction(
                    leading = icon(Icons.Outlined.KeyguardCipher),
                    title = Res.string.items.wrap(),
                    onClick = onClick {
                        val accounts = selectedAccounts.values
                        val route = VaultRoute.by(accounts = accounts)
                        val intent = NavigationIntent.NavigateToRoute(route)
                        navigate(intent)
                    },
                )
            }
            section {
                this += FlatItemAction(
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    title = Res.string.account_action_log_out_title.wrap(),
                    onClick = onClick {
                        onDelete(f)
                    },
                )
            }
        }
        AccountListState.Selection(
            count = selectedAccounts.size,
            actions = actions.toImmutableList(),
            onSelectAll = selectionHandle::setSelection
                .partially1(accountIds.map { it.id }.toSet())
                .takeIf {
                    accountIds.size > f.size
                },
            onSync = ::onSync
                .partially1(accountIds),
            onClear = selectionHandle::clearSelection,
        )
    }

    fun flowOfItems(
        accounts: List<DAccount>,
        profiles: List<DProfile>,
    ) = accounts
        .map {
            val profile = profiles.firstOrNull(it.id)

            val errorFlow = getAccountHasError(it.id)
            val syncingFlow = getIsAccountSyncingFlow(it.id)
            val removingFlow = getIsAccountRemovingFlow(it.id)
            combine(
                errorFlow,
                syncingFlow,
                removingFlow,
                selectionHandle
                    .idsFlow
                    .map { accountIds ->
                        it.id.id in accountIds
                    },
                selectionModeFlow,
            ) { error, syncing, removing, selected, selectionMode ->
                val busy = syncing || removing
                val accent = profile?.accentColor
                    ?: generateAccentColorsByAccountId(it.id.id)
                val icon = VaultItemIcon.TextIcon.short(profile?.name.orEmpty())
                val title = profile?.displayName?.let(::AnnotatedString)
                AccountItem.Item(
                    id = it.id.id,
                    icon = icon,
                    name = profile?.name.orEmpty(),
                    title = title,
                    text = it.host,
                    error = error,
                    hidden = profile?.hidden == true,
                    premium = profile?.premium == true,
                    syncing = syncing,
                    selecting = selectionMode,
                    actions = listOf(),
                    actionNeeded = false,
                    accentLight = accent.light,
                    accentDark = accent.dark,
                    isOpened = false,
                    isSelected = selected,
                    onClick = {
                        if (selectionMode) {
                            selectionHandle.toggleSelection(it.id.id)
                        } else {
                            val route = AccountViewRoute(it.id)
                            val intent = NavigationIntent.Composite(
                                listOf(
                                    NavigationIntent.PopById(rootRouterName),
                                    NavigationIntent.NavigateToRoute(route),
                                ),
                            )
                            navigate(intent)
                        }
                    },
                    onLongClick = if (selectionMode) {
                        null
                    } else {
                        // lambda
                        selectionHandle::toggleSelection.partially1(it.id.id)
                    },
                )
            }
        }
        .foldAsList()
        .map { items ->
            items
                .sortedWith(StringComparatorIgnoreCase { it.text })
        }

    combine(
        combine(
            getAccounts(),
            getProfiles(),
        ) { accounts, profiles ->
            flowOfItems(
                accounts = accounts,
                profiles = profiles,
            )
        }
            .flatMapLatest { it }
            .map {
                val list = mutableListOf<AccountItem>()
                if (it.isNotEmpty()) {
                    list += AccountItem.Section(
                        id = "bitwarden",
                        text = "Bitwarden",
                    )
                    list += it
                }
                list
            },
        selectionFlow,
        getCanAddAccount(),
    ) { items, selection, canAddAccount ->
        AccountListStateWrapper { onAddAccount ->
            val addNewAccountOptions = if (canAddAccount && selection == null) {
                buildContextItems {
                    this += FlatItemAction(
                        leading = icon(Icons.Outlined.Cloud),
                        title = TextHolder.Value(AccountType.BITWARDEN.fullName),
                        text = TextHolder.Res(Res.string.addaccount_description_short_bitwarden_text),
                        trailing = if (AccountType.BITWARDEN.beta) {
                            // composable
                            {
                                BetaBadge()
                            }
                        } else null,
                        onClick = onAddAccount
                            .partially1(AccountType.BITWARDEN),
                    )
                    this += FlatItemAction(
                        leading = icon(Icons.Outlined.FilePresent),
                        title = TextHolder.Value(AccountType.KEEPASS.fullName),
                        text = TextHolder.Res(Res.string.addaccount_description_short_keepass_text),
                        trailing = if (AccountType.KEEPASS.beta) {
                            // composable
                            {
                                BetaBadge()
                            }
                        } else null,
                        onClick = onAddAccount
                            .partially1(AccountType.KEEPASS),
                    )
                }
            } else emptyList()
            AccountListState(
                selection = selection,
                items = items,
                isLoading = false,
                addNewAccountOptions = addNewAccountOptions,
                onAddNewAccount = onAddAccount
                    .partially1(AccountType.BITWARDEN)
                    .takeIf { canAddAccount && selection == null },
            )
        }
    }
}
