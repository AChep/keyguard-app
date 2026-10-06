package com.artemchep.keyguard.apple.account

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.usecase.GetAccountStatus
import com.artemchep.keyguard.feature.auth.AccountViewState
import com.artemchep.keyguard.feature.auth.accountStateProducer
import com.artemchep.keyguard.feature.home.settings.accounts.AccountListState
import com.artemchep.keyguard.feature.home.settings.accounts.accountListScreenStateProducer
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.account_action_show_title
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.dialog.DialogController
import com.artemchep.keyguard.apple.model.ActionKeyAllocator
import com.artemchep.keyguard.apple.model.VaultActionSnapshot
import com.artemchep.keyguard.apple.model.buildSelectionActionSnapshots
import com.artemchep.keyguard.apple.model.buildVaultItemSnapshots
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import org.koin.core.scope.Scope

internal class AccountsController(
    private val ctx: CoreContext,
    private val dialogController: DialogController,
) {
    /**
     * Defaults to the dialog-only interceptor; [KeyguardCore] late-binds it to the
     * navigation stack so the account's "view items" (a [VaultRoute]) pushes onto
     * the Settings-scope stack instead of being dropped.
     */
    var navigationInterceptorProvider: (Scope) -> ((NavigationIntent) -> Boolean) =
        { sessionKoin -> dialogController.navigationInterceptor(sessionKoin = sessionKoin) }

    /** List actions are separate from each presentation's detail session. */
    private var accountListActionHandlers: Map<String, () -> Unit> = emptyMap()

    fun observeAccountList(
        onChange: (AccountListSnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchSessionObserver(
            onLocked = {
                accountListActionHandlers = emptyMap()
                onChange(AccountListSnapshot.empty)
            },
        ) { state ->
            val producerScope = this
            // The selection's bulk actions navigate; thread the same interceptor the detail
            // pane uses so those intents are handled instead of being dropped.
            val producerFlow = with(state.sessionKoin) {
                ctx.koin.newHeadlessStateFlowScope(
                    "account_list",
                    producerScope,
                    navigationInterceptorProvider(state.sessionKoin),
                )
                    .accountListScreenStateProducer(
                        rootRouterName = null,
                        queueSyncById = get(),
                        syncSupervisor = get(),
                        removeAccountById = get(),
                        getAccounts = get(),
                        getProfiles = get(),
                        getAccountHasError = get(),
                        getCanAddAccount = get(),
                        vaultRouteFactory = get(),
                        accountViewRouteFactory = get(),
                        confirmationRouteFactory = get(),
                        windowCoroutineScope = get(),
                    )
            }
            producerFlow
                .map { wrapper ->
                    // SwiftUI owns account-list navigation by id.
                    val accountListState = wrapper.unwrap(onAddAccount = {})
                    val handlers = LinkedHashMap<String, () -> Unit>()
                    val snapshot = buildAccountListSnapshot(accountListState, leContext, handlers)
                    snapshot to handlers
                }
                .collectOnMain { (snapshot, handlers) ->
                    accountListActionHandlers = handlers
                    onChange(snapshot)
                }
        }
    }

    private suspend fun buildAccountListSnapshot(
        state: AccountListState,
        leContext: LeContext,
        handlers: LinkedHashMap<String, () -> Unit>,
    ): AccountListSnapshot {
        val selection = state.selection
        val selecting = selection != null
        val items = state.items.mapNotNull { item ->
            when (item) {
                is AccountItem.Item -> {
                    // The per-row tap (while selecting) / long-press (to start a
                    // selection) both toggle this account's membership; register both
                    // under one id so the Swift checkmark drives the producer selection.
                    val toggleActionId = (item.onClick.takeIf { selecting } ?: item.onLongClick)
                        ?.let { onClick ->
                            val id = "${item.id}:toggle"
                            handlers[id] = onClick
                            id
                        }
                    AccountListItemSnapshot(
                        id = item.id,
                        accountId = item.id,
                        name = item.name,
                        title = item.title?.text?.takeIf { it.isNotBlank() } ?: item.name,
                        host = item.text,
                        error = item.error,
                        hidden = item.hidden,
                        premium = item.premium,
                        syncing = item.syncing,
                        selecting = item.selecting,
                        selected = item.isSelected,
                        toggleActionId = toggleActionId,
                    )
                }

                is AccountItem.Section -> null
            }
        }

        // Besides the overflow actions, the Compose AccountsSelection bar renders dedicated
        // Sync / Select-all / Clear affordances.
        val selectionActions =
            buildSelectionActionSnapshots(selection?.actions, leContext, handlers)
        val selectionSyncActionId = selection?.onSync?.let { onSync ->
            handlers["selection:sync"] = onSync
            "selection:sync"
        }
        val selectionSelectAllActionId = selection?.onSelectAll?.let { onSelectAll ->
            handlers["selection:select_all"] = onSelectAll
            "selection:select_all"
        }
        val selectionClearActionId = selection?.onClear?.let { onClear ->
            handlers["selection:clear"] = onClear
            "selection:clear"
        }

        return AccountListSnapshot(
            loaded = !state.isLoading,
            items = items,
            selectionCount = selection?.count ?: 0,
            selectionActions = selectionActions,
            selectionSyncActionId = selectionSyncActionId,
            selectionSelectAllActionId = selectionSelectAllActionId,
            selectionClearActionId = selectionClearActionId,
        )
    }

    fun invokeAccountListAction(id: String) {
        accountListActionHandlers.invokeAction(id)
    }

    fun observeSyncStatus(
        onChange: (SyncStatusSnapshot) -> Unit,
    ): KeyguardCancellable {
        return ctx.launchSessionObserver(
            onLocked = {
                onChange(SyncStatusSnapshot.empty)
            },
        ) { state ->
            val getAccountStatus: GetAccountStatus = state.sessionKoin.get()
            getAccountStatus()
                .map { status ->
                    SyncStatusSnapshot(
                        loaded = true,
                        errorCount = status.error?.count ?: 0,
                        pendingCount = status.pending?.count ?: 0,
                        lastSyncTimestampMs = status.lastSyncTimestamp?.toEpochMilliseconds(),
                    )
                }
                .collectOnMain { onChange(it) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun makeDetailSession(
        accountId: String,
    ): AccountDetailSession = AccountDetailSession { publish ->
        val leContext = ctx.koin.get<LeContext>()
        ctx.launchSessionObserver(
            onLocked = {
                publish(AccountDetailSnapshot.empty, emptyMap())
            },
        ) { state ->
            val producerScope = this
            val producerFlow = with(state.sessionKoin) {
                ctx.koin.newHeadlessStateFlowScope(
                    "account_view",
                    producerScope,
                    navigationInterceptorProvider(state.sessionKoin),
                )
                    .accountStateProducer(
                        queueSyncById = get(),
                        syncSupervisor = get(),
                        getGravatarUrl = get(),
                        clipboardService = get(),
                        dateFormatter = get(),
                        removeAccountById = get(),
                        getFingerprint = get(),
                        putAccountNameById = get(),
                        putAccountColorById = get(),
                        putAccountMasterPasswordHintById = get(),
                        putProfileHidden = get(),
                        getAccounts = get(),
                        getProfiles = get(),
                        getCiphers = get(),
                        getSends = get(),
                        getEquivalentDomains = get(),
                        getFolders = get(),
                        getCollections = get(),
                        getOrganizations = get(),
                        getMetas = get(),
                        bitwardenLoginRouteFactory = get(),
                        confirmationRouteFactory = get(),
                        vaultRouteFactory = get(),
                        sendRouteFactory = get(),
                        collectionsRouteFactory = get(),
                        foldersRouteFactory = get(),
                        organizationsRouteFactory = get(),
                        db = get(),
                        accountId = AccountId(accountId),
                    )
            }
            producerFlow
                .map { accountState ->
                    val actionHandlers = LinkedHashMap<String, () -> Unit>()
                    val snapshot = buildAccountDetailSnapshot(accountState, leContext, actionHandlers)
                    snapshot to actionHandlers
                }
                .collectOnMain { (snapshot, actionHandlers) ->
                    publish(snapshot, actionHandlers)
                }
        }
    }

    private suspend fun buildAccountDetailSnapshot(
        state: AccountViewState,
        leContext: LeContext,
        actionHandlers: LinkedHashMap<String, () -> Unit>,
    ): AccountDetailSnapshot {
        return when (val content = state.content) {
            is AccountViewState.Content.Data -> {
                val data = content.data
                val items = buildVaultItemSnapshots(
                    items = content.items,
                    notesText = null,
                    leContext = leContext,
                    actionHandlers = actionHandlers,
                )

                val actionKeys = ActionKeyAllocator("account")
                val headerActions = ArrayList<VaultActionSnapshot>()
                for (ci in content.actions) {
                    if (ci !is FlatItemAction) continue
                    // Native menus need an explicit inverse action: their
                    // checkmark is not consistently exposed to accessibility.
                    val title = if (ci.id == "account.hideProfile" && ci.selected) {
                        textResource(Res.string.account_action_show_title, leContext)
                    } else {
                        textResource(ci.title, leContext)
                    }
                    val actionId = actionKeys.keyFor(ci, title)
                    ci.onClick?.let { actionHandlers[actionId] = it }
                    headerActions += VaultActionSnapshot(
                        id = actionId,
                        title = title,
                        isCopy = ci.type == FlatItemAction.Type.COPY,
                        danger = ci.danger,
                    )
                }

                // The open-web-vault / open-local-vault closures are not part of
                // content.items, so register them under stable ids; Swift renders
                // a header button when the matching id is non-null (web vault for
                // Bitwarden, reveal-the-kdbx-file for KeePass).
                val openWebVaultActionId = content.onOpenWebVault?.let {
                    actionHandlers["account:openWebVault"] = it
                    "account:openWebVault"
                }
                val openLocalVaultActionId = content.onOpenLocalVault?.let {
                    actionHandlers["account:openLocalVault"] = it
                    "account:openLocalVault"
                }

                AccountDetailSnapshot(
                    loaded = true,
                    notFound = false,
                    title = data.username?.takeIf { it.isNotBlank() } ?: data.host,
                    host = data.host,
                    email = data.username,
                    accountType = data.type.name,
                    openWebVaultActionId = openWebVaultActionId,
                    openLocalVaultActionId = openLocalVaultActionId,
                    items = items,
                    actions = headerActions,
                )
            }

            is AccountViewState.Content.NotFound ->
                AccountDetailSnapshot.empty.copy(notFound = true)

            is AccountViewState.Content.Skeleton ->
                AccountDetailSnapshot.empty
        }
    }
}
