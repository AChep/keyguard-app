package com.artemchep.keyguard.apple.watchtower

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.CheckHibpApiToken
import com.artemchep.keyguard.common.usecase.GetCheckPasskeys
import com.artemchep.keyguard.common.usecase.GetCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.GetCheckPwnedServices
import com.artemchep.keyguard.common.usecase.GetCheckTwoFA
import com.artemchep.keyguard.common.usecase.GetHibpApiToken
import com.artemchep.keyguard.common.usecase.PutCheckPasskeys
import com.artemchep.keyguard.common.usecase.PutCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.PutCheckPwnedServices
import com.artemchep.keyguard.common.usecase.PutCheckTwoFA
import com.artemchep.keyguard.common.usecase.PutHibpApiToken
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.watchtower.WatchtowerRoute
import com.artemchep.keyguard.feature.watchtower.WatchtowerState
import com.artemchep.keyguard.feature.watchtower.alerts.WatchtowerAlertsRoute
import com.artemchep.keyguard.feature.watchtower.alerts.WatchtowerNewAlertsState
import com.artemchep.keyguard.feature.watchtower.alerts.watchtowerNewAlertsStateProducer
import com.artemchep.keyguard.feature.watchtower.watchtowerStateProducer
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.model.ActionKeyAllocator
import com.artemchep.keyguard.apple.model.VaultFilterItemKind
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.apple.model.mapFilterItemsToSnapshots
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.StringResource
import org.koin.core.scope.Scope

private val HIBP_API_TOKEN_REGEX = Regex("^[0-9a-fA-F]{32}$")

/** Watchtower dashboard, alerts, and settings projected from shared producers. */
internal class WatchtowerController(
    private val ctx: CoreContext,
    private val args: WatchtowerRoute.Args = WatchtowerRoute.Args(),
    private val persistenceScope: String = "watchtower",
) {
    private val getCheckPwnedPasswords: GetCheckPwnedPasswords by lazy { ctx.koin.get() }
    private val putCheckPwnedPasswords: PutCheckPwnedPasswords by lazy { ctx.koin.get() }
    private val getCheckPwnedServices: GetCheckPwnedServices by lazy { ctx.koin.get() }
    private val putCheckPwnedServices: PutCheckPwnedServices by lazy { ctx.koin.get() }
    private val getCheckTwoFA: GetCheckTwoFA by lazy { ctx.koin.get() }
    private val putCheckTwoFA: PutCheckTwoFA by lazy { ctx.koin.get() }
    private val getCheckPasskeys: GetCheckPasskeys by lazy { ctx.koin.get() }
    private val putCheckPasskeys: PutCheckPasskeys by lazy { ctx.koin.get() }

    /** Navigation interceptor for dashboard routes; null drops unhandled routes. */
    var navigationInterceptorProvider: ((Scope) -> ((NavigationIntent) -> Boolean))? = null

    private var watchtowerActionHandlers: Map<String, () -> Unit> = emptyMap()
    private var latestWatchtowerState: WatchtowerState? = null
    private var watchtowerFilterHandlers: Map<String, () -> Unit> = emptyMap()

    // Per-alert "open the affected cipher" closures (keyed by the alert id) and
    // the "mark all as read" closure, captured from the latest alerts state so
    // their taps route through the bound nav interceptor.
    private var watchtowerAlertItemHandlers: Map<String, () -> Unit> = emptyMap()
    private var watchtowerMarkAllReadHandler: (() -> Unit)? = null

    // GetHibpApiToken / PutHibpApiToken / CheckHibpApiToken are bound in the
    // unlocked session sub-DI, so they're resolved per-session inside
    // observeWatchtowerSettings. The Put is cached for setHibpApiToken.
    private var watchtowerPutHibpApiToken: PutHibpApiToken? = null

    // Only the newest settings observer owns the putter, so a stale teardown
    // can't clear the one a reopened screen just published.
    private var settingsObservationGeneration = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeWatchtower(
        onChange: (WatchtowerSnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchSessionObserver(
            onLocked = {
                watchtowerActionHandlers = emptyMap()
                watchtowerFilterHandlers = emptyMap()
                latestWatchtowerState = null
                onChange(WatchtowerSnapshot.empty)
            },
        ) { state ->
            val producerScope = this
            val interceptor = navigationInterceptorProvider?.invoke(state.sessionKoin)
            val producerFlow = with(state.sessionKoin) {
                ctx.koin.newHeadlessStateFlowScope(persistenceScope, producerScope, interceptor)
                    .watchtowerStateProducer(
                        filterContext = get(),
                        addCipherFilter = get(),
                        confirmationRouteFactory = get(),
                        getCipherFilters = get(),
                        args = args,
                        getCiphers = get(),
                        getAccounts = get(),
                        getProfiles = get(),
                        getFolders = get(),
                        getTags = get(),
                        getCollections = get(),
                        getOrganizations = get(),
                        getCheckPwnedPasswords = get(),
                        getCheckPwnedServices = get(),
                        getCheckTwoFA = get(),
                        getCheckPasskeys = get(),
                        getWatchtowerAlerts = get(),
                        getWatchtowerUnreadAlerts = get(),
                        cipherDuplicatesCheck = get(),
                        dismissNotificationsByChannel = get(),
                        foldersRouteFactory = get(),
                        vaultRouteFactory = get(),
                    )
            }
            // Single shared copy of the latest top-level state; the
            // producer is cold, so fan out from one StateFlow.
            val latest = producerFlow.stateIn<WatchtowerState?>(this, SharingStarted.Eagerly, null)

            // The live counters live behind inner StateFlows in
            // the state's Content that the top-level state does NOT
            // re-emit for, so combine them in: any counter tick
            // re-runs the builder and pushes a fresh snapshot. Key the
            // inner subscription on the stable Content instance:
            // resubscribing per top-level emission would cancel and
            // restart every counter pipeline (duplicates scan included).
            val innerTickFlow = latest
                .filterNotNull()
                .map { wt -> wt.content.getOrNull() }
                .distinctUntilChanged { a, b -> a === b }
                .flatMapLatest { content ->
                    if (content == null) {
                        flowOf(Unit)
                    } else {
                        val innerFlows = listOf<Flow<Any?>>(
                            content.unreadThreats,
                            content.unsecureWebsites,
                            content.duplicateWebsites,
                            content.broadWebsites,
                            content.inactiveTwoFactorAuth,
                            content.inactivePasskey,
                            content.pwned,
                            content.pwnedWebsites,
                            content.reused,
                            content.weakSshKeys,
                            content.weakGpgKeys,
                            content.unusableGpgKeys,
                            content.gpgKeyPublishing,
                            content.incompleteItems,
                            content.expiringItems,
                            content.duplicateItems,
                            content.trashedItems,
                            content.emptyItems,
                            content.strength,
                        )
                        combine(innerFlows) { }
                    }
                }
            innerTickFlow
                .combine(latest.filterNotNull()) { _, wt -> wt }
                // The builder reads the counters' current .value, so
                // a burst of ticks while a snapshot is being built
                // collapses into one rebuild without losing data.
                .conflate()
                .collect { wt ->
                    val actionHandlers = LinkedHashMap<String, () -> Unit>()
                    val filterHandlers = LinkedHashMap<String, () -> Unit>()
                    val snapshot = buildWatchtowerSnapshot(wt, leContext, actionHandlers, filterHandlers)
                    ctx.publishOnMain {
                        latestWatchtowerState = wt
                        watchtowerActionHandlers = actionHandlers
                        watchtowerFilterHandlers = filterHandlers
                        onChange(snapshot)
                    }
                }
        }
    }

    private suspend fun buildWatchtowerSnapshot(
        state: WatchtowerState,
        leContext: LeContext,
        actionHandlers: LinkedHashMap<String, () -> Unit>,
        filterHandlers: LinkedHashMap<String, () -> Unit>,
    ): WatchtowerSnapshot {
        // Filter tree (same shape as the vault list filters).
        val filters = mapFilterItemsToSnapshots(state.filter.items, filterHandlers)
        val canClearFilters = state.filter.onClear != null
        val activeFilterCount = filters.count { it.kind == VaultFilterItemKind.ITEM && it.checked }

        // Toolbar directory shortcuts (2FA / passkeys / just-get-my-data / etc.).
        val optionKeys = ActionKeyAllocator("option")
        val options = ArrayList<WatchtowerOptionSnapshot>()
        state.actions.forEach { ci ->
            if (ci !is FlatItemAction) return@forEach
            val title = textResource(ci.title, leContext)
            val id = optionKeys.keyFor(ci, title)
            ci.onClick?.let { actionHandlers[id] = it }
            options += WatchtowerOptionSnapshot(
                id = id,
                title = title,
            )
        }

        val content = state.content.getOrNull()
        if (content == null) {
            return WatchtowerSnapshot.empty.copy(
                options = options,
                filters = filters,
                canClearFilters = canClearFilters,
                activeFilterCount = activeFilterCount,
            )
        }

        suspend fun card(
            out: ArrayList<WatchtowerCardSnapshot>,
            id: String,
            title: StringResource,
            text: StringResource,
            count: Int,
            new: Int,
            status: WatchtowerCardStatus,
            onClick: (() -> Unit)?,
        ) {
            onClick?.let { actionHandlers[id] = it }
            out += WatchtowerCardSnapshot(
                id = id,
                title = textResource(title, leContext),
                text = textResource(text, leContext),
                count = count,
                new = new,
                status = status,
                canClick = onClick != null,
            )
        }

        fun errStatus(count: Int) =
            if (count > 0) WatchtowerCardStatus.ERROR else WatchtowerCardStatus.OK

        fun warnStatus(count: Int) =
            if (count > 0) WatchtowerCardStatus.WARNING else WatchtowerCardStatus.OK

        fun infoStatus(count: Int) =
            if (count > 0) WatchtowerCardStatus.INFO else WatchtowerCardStatus.OK

        // New alerts row.
        val unread = content.unreadThreats.value.getOrNull()
        unread?.onClick?.let { actionHandlers["unread"] = it }

        // Security section (WatchtowerScreen order).
        val security = ArrayList<WatchtowerCardSnapshot>()
        content.pwned.value.getOrNull()?.let {
            card(
                security,
                "pwned",
                Res.string.watchtower_item_pwned_passwords_title,
                Res.string.watchtower_item_pwned_passwords_text,
                it.count,
                it.new,
                errStatus(it.count),
                it.onClick,
            )
        }
        content.pwnedWebsites.value.getOrNull()?.let {
            card(
                security,
                "pwnedWebsites",
                Res.string.watchtower_item_vulnerable_accounts_title,
                Res.string.watchtower_item_vulnerable_accounts_text,
                it.count,
                it.new,
                errStatus(it.count),
                it.onClick,
            )
        }
        content.reused.value.getOrNull()?.let {
            card(
                security,
                "reused",
                Res.string.watchtower_item_reused_passwords_title,
                Res.string.watchtower_item_reused_passwords_text,
                it.count,
                it.new,
                errStatus(it.count),
                it.onClick,
            )
        }
        content.weakSshKeys.value.getOrNull()?.let {
            card(
                security,
                "weakSshKeys",
                Res.string.watchtower_item_weak_ssh_keys_title,
                Res.string.watchtower_item_weak_ssh_keys_text,
                it.count,
                it.new,
                errStatus(it.count),
                it.onClick,
            )
        }
        content.weakGpgKeys.value.getOrNull()?.let {
            card(
                security,
                "weakGpgKeys",
                Res.string.watchtower_item_weak_gpg_keys_title,
                Res.string.watchtower_item_weak_gpg_keys_text,
                it.count,
                it.new,
                errStatus(it.count),
                it.onClick,
            )
        }
        content.inactiveTwoFactorAuth.value.getOrNull()?.let {
            card(
                security,
                "inactiveTwoFactorAuth",
                Res.string.watchtower_item_inactive_2fa_title,
                Res.string.watchtower_item_inactive_2fa_text,
                it.count,
                it.new,
                warnStatus(it.count),
                it.onClick,
            )
        }
        content.unsecureWebsites.value.getOrNull()?.let {
            card(
                security,
                "unsecureWebsites",
                Res.string.watchtower_item_unsecure_websites_title,
                Res.string.watchtower_item_unsecure_websites_text,
                it.count,
                it.new,
                warnStatus(it.count),
                it.onClick,
            )
        }
        content.inactivePasskey.value.getOrNull()?.let {
            card(
                security,
                "inactivePasskey",
                Res.string.watchtower_item_inactive_passkey_title,
                Res.string.watchtower_item_inactive_passkey_text,
                it.count,
                it.new,
                infoStatus(it.count),
                it.onClick,
            )
        }

        // Maintenance section (WatchtowerScreen order).
        val maintenance = ArrayList<WatchtowerCardSnapshot>()
        content.duplicateItems.value.getOrNull()?.let {
            card(
                maintenance,
                "duplicateItems",
                Res.string.watchtower_item_duplicate_items_title,
                Res.string.watchtower_item_duplicate_items_text,
                it.count,
                it.new,
                infoStatus(it.count),
                it.onClick,
            )
        }
        content.incompleteItems.value.getOrNull()?.let {
            card(
                maintenance,
                "incompleteItems",
                Res.string.watchtower_item_incomplete_items_title,
                Res.string.watchtower_item_incomplete_items_text,
                it.count,
                it.new,
                infoStatus(it.count),
                it.onClick,
            )
        }
        content.expiringItems.value.getOrNull()?.let {
            card(
                maintenance,
                "expiringItems",
                Res.string.watchtower_item_expiring_items_title,
                Res.string.watchtower_item_expiring_items_text,
                it.count,
                it.new,
                infoStatus(it.count),
                it.onClick,
            )
        }
        content.unusableGpgKeys.value.getOrNull()?.let {
            card(
                maintenance,
                "unusableGpgKeys",
                Res.string.watchtower_item_unusable_gpg_keys_title,
                Res.string.watchtower_item_unusable_gpg_keys_text,
                it.count,
                it.new,
                infoStatus(it.count),
                it.onClick,
            )
        }
        content.gpgKeyPublishing.value.getOrNull()?.let {
            card(
                maintenance,
                "gpgKeyPublishing",
                Res.string.watchtower_item_gpg_key_publishing_title,
                Res.string.watchtower_item_gpg_key_publishing_text,
                it.count,
                it.new,
                infoStatus(it.count),
                it.onClick,
            )
        }
        content.duplicateWebsites.value.getOrNull()?.let {
            card(
                maintenance,
                "duplicateWebsites",
                Res.string.watchtower_item_duplicate_websites_title,
                Res.string.watchtower_item_duplicate_websites_text,
                it.count,
                it.new,
                infoStatus(it.count),
                it.onClick,
            )
        }
        content.broadWebsites.value.getOrNull()?.let {
            card(
                maintenance,
                "broadWebsites",
                Res.string.watchtower_item_broad_websites_title,
                Res.string.watchtower_item_broad_websites_text,
                it.count,
                it.new,
                infoStatus(it.count),
                it.onClick,
            )
        }
        content.trashedItems.value.getOrNull()?.let {
            val status = when {
                it.count > 2000 -> WatchtowerCardStatus.ERROR
                it.count > 500 -> WatchtowerCardStatus.WARNING
                it.count > 0 -> WatchtowerCardStatus.INFO
                else -> WatchtowerCardStatus.OK
            }
            card(
                maintenance,
                "trashedItems",
                Res.string.watchtower_item_trashed_items_title,
                Res.string.watchtower_item_trashed_items_text,
                it.count,
                0,
                status,
                it.onClick,
            )
        }
        content.emptyItems.value.getOrNull()?.let {
            val status = when {
                it.count > 50 -> WatchtowerCardStatus.ERROR
                it.count > 5 -> WatchtowerCardStatus.WARNING
                it.count > 0 -> WatchtowerCardStatus.INFO
                else -> WatchtowerCardStatus.OK
            }
            card(
                maintenance,
                "emptyItems",
                Res.string.watchtower_item_empty_folders_title,
                Res.string.watchtower_item_empty_folders_text,
                it.count,
                it.new,
                status,
                it.onClick,
            )
        }

        // Password-strength distribution (strongest first, like the producer).
        val strength = ArrayList<WatchtowerStrengthSnapshot>()
        content.strength.value.getOrNull()?.let { ps ->
            ps.items.forEach { item ->
                val id = "strength:${item.score.name}"
                item.onClick?.let { actionHandlers[id] = it }
                strength += WatchtowerStrengthSnapshot(
                    id = id,
                    score = item.score.name,
                    count = item.count,
                    new = item.new,
                    canClick = item.onClick != null,
                )
            }
        }

        return WatchtowerSnapshot(
            loaded = true,
            unreadCount = unread?.count ?: 0,
            canClickUnread = unread?.onClick != null,
            security = security,
            maintenance = maintenance,
            strength = strength,
            options = options,
            filters = filters,
            canClearFilters = canClearFilters,
            activeFilterCount = activeFilterCount,
        )
    }

    /** Invokes a watchtower navigation closure by its fixed snapshot id. */
    fun invokeWatchtowerAction(id: String) {
        watchtowerActionHandlers.invokeAction(id)
    }

    /** Toggles a watchtower filter on / off (or expands / collapses a section). */
    fun invokeWatchtowerFilter(id: String) {
        watchtowerFilterHandlers[id]?.invoke()
    }

    /** Clears every active watchtower filter. No-op unless any filter is set. */
    fun clearWatchtowerFilters() {
        latestWatchtowerState?.filter?.onClear?.invoke()
    }

    fun observeWatchtowerNewAlerts(
        alertArgs: WatchtowerAlertsRoute.Args = WatchtowerAlertsRoute.Args(),
        onChange: (WatchtowerAlertsSnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchSessionObserver(
            onLocked = {
                watchtowerAlertItemHandlers = emptyMap()
                watchtowerMarkAllReadHandler = null
                onChange(WatchtowerAlertsSnapshot.empty)
            },
        ) { state ->
            val producerScope = this
            // Thread the stack interceptor so tapping an alert row (which emits a
            // NavigateToRoute(VaultViewRoute)) pushes the cipher detail onto the
            // current scope instead of being dropped by the NoOp controller.
            val interceptor = navigationInterceptorProvider?.invoke(state.sessionKoin)
            val producerFlow = with(state.sessionKoin) {
                ctx.koin.newHeadlessStateFlowScope("${persistenceScope}_new_alerts", producerScope, interceptor)
                    .watchtowerNewAlertsStateProducer(
                        filterContext = get(),
                        args = alertArgs,
                        markAllWatchtowerAlertAsRead = get(),
                        markWatchtowerAlertsAsRead = get(),
                        getProfiles = get(),
                        getOrganizations = get(),
                        getCiphers = get(),
                        getWatchtowerAlerts = get(),
                        getTotpCode = get(),
                        getConcealFields = get(),
                        getAppIcons = get(),
                        getWebsiteIcons = get(),
                        dateFormatter = get(),
                        clipboardService = get(),
                        dismissNotificationsByChannel = get(),
                    )
            }
            producerFlow
                .collect { loadable ->
                    val itemHandlers = LinkedHashMap<String, () -> Unit>()
                    val state = loadable.getOrNull()
                    val snapshot = buildWatchtowerAlertsSnapshot(state, leContext, itemHandlers)
                    ctx.publishOnMain {
                        watchtowerAlertItemHandlers = itemHandlers
                        watchtowerMarkAllReadHandler = state?.onMarkAllRead
                        onChange(snapshot)
                    }
                }
        }
    }

    private suspend fun buildWatchtowerAlertsSnapshot(
        state: WatchtowerNewAlertsState?,
        leContext: LeContext,
        itemHandlers: LinkedHashMap<String, () -> Unit>,
    ): WatchtowerAlertsSnapshot {
        state ?: return WatchtowerAlertsSnapshot.empty
        val items = state.items.map { item ->
            when (item) {
                is WatchtowerNewAlertsState.Item.Section -> WatchtowerAlertItemSnapshot(
                    id = item.id,
                    kind = WatchtowerAlertItemKind.SECTION,
                    title = textResource(item.text, leContext).orEmpty(),
                    text = "",
                    date = null,
                    read = true,
                )

                is WatchtowerNewAlertsState.Item.Alert -> {
                    // The cipher row carries the "open the affected item" closure
                    // as its Go action; capture it so the row tap routes through
                    // the bound nav interceptor (NavigateToRoute(VaultViewRoute)).
                    val onClick = (item.item.action as? VaultItem2.Item.Action.Go)?.onClick
                    onClick?.let { itemHandlers[item.id] = it }
                    WatchtowerAlertItemSnapshot(
                        id = item.id,
                        kind = WatchtowerAlertItemKind.ALERT,
                        title = item.cipher.name,
                        text = textResource(item.type.title, leContext),
                        date = item.date,
                        read = item.read,
                        canClick = onClick != null,
                    )
                }
            }
        }
        return WatchtowerAlertsSnapshot(
            loaded = true,
            items = items,
            canMarkAllRead = items.any { it.kind == WatchtowerAlertItemKind.ALERT },
        )
    }

    /** Opens the cipher affected by a watchtower alert by its alert id. */
    fun invokeWatchtowerAlertItem(id: String) {
        watchtowerAlertItemHandlers[id]?.invoke()
    }

    /** Marks every watchtower alert as read (and pops the alerts list). */
    fun markAllWatchtowerAlertsRead() {
        watchtowerMarkAllReadHandler?.invoke()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeWatchtowerSettings(
        onChange: (WatchtowerSettingsSnapshot) -> Unit,
    ): KeyguardCancellable {
        val observationGeneration = ++settingsObservationGeneration
        return ctx.launchSessionObserver(
            onLocked = {
                if (settingsObservationGeneration == observationGeneration) {
                    watchtowerPutHibpApiToken = null
                }
                onChange(WatchtowerSettingsSnapshot.empty)
            },
            onTeardown = {
                if (settingsObservationGeneration == observationGeneration) {
                    watchtowerPutHibpApiToken = null
                }
            },
        ) { state ->
            val sessionKoin = state.sessionKoin
            val getHibpApiToken = sessionKoin.get<GetHibpApiToken>()
            val checkHibpApiToken = sessionKoin.get<CheckHibpApiToken>()
            val putHibpApiToken = sessionKoin.get<PutHibpApiToken>()
            // setHibpApiToken reads the putter on the main thread.
            ctx.publishOnMain {
                if (settingsObservationGeneration == observationGeneration) {
                    watchtowerPutHibpApiToken = putHibpApiToken
                }
            }
            val hibpFlow = getHibpApiToken()
                .distinctUntilChanged()
                .flatMapLatest { token -> hibpCheckStateFlow(token, checkHibpApiToken) }
            combine(
                getCheckPwnedPasswords(),
                getCheckPwnedServices(),
                getCheckTwoFA(),
                getCheckPasskeys(),
                hibpFlow,
            ) { pwnedPasswords, pwnedServices, twoFa, passkeys, hibp ->
                WatchtowerSettingsSnapshot(
                    loaded = true,
                    checkPwnedPasswords = pwnedPasswords,
                    checkPwnedServices = pwnedServices,
                    checkTwoFa = twoFa,
                    checkPasskeys = passkeys,
                    hibpApiToken = hibp.first,
                    hibpCheckState = hibp.second,
                )
            }.collectOnMain { snapshot ->
                onChange(snapshot)
            }
        }
    }

    /**
     * Emits the HIBP token paired with its verification state ("checking" ->
     * "verified" / "rejected" / "failed", or null when blank).
     */
    private fun hibpCheckStateFlow(
        token: String?,
        checkHibpApiToken: CheckHibpApiToken,
    ): Flow<Pair<String?, String?>> {
        val normalized = token?.takeIf { it.isNotBlank() }
            ?: return flowOf(null to null)
        return flow {
            emit(normalized to "checking")
            val result = checkHibpApiToken(normalized)
                .attempt()
                .bind()
            val state = result.fold(
                ifLeft = { e ->
                    if (e is HttpException && e.statusCode == HttpStatusCode.Unauthorized) {
                        "rejected"
                    } else {
                        "failed"
                    }
                },
                ifRight = { "verified" },
            )
            emit(normalized to state)
        }
    }

    fun setCheckPwnedPasswords(value: Boolean) {
        putCheckPwnedPasswords(value).launchIn(ctx.scope)
    }

    fun setCheckPwnedServices(value: Boolean) {
        putCheckPwnedServices(value).launchIn(ctx.scope)
    }

    fun setCheckTwoFa(value: Boolean) {
        putCheckTwoFA(value).launchIn(ctx.scope)
    }

    fun setCheckPasskeys(value: Boolean) {
        putCheckPasskeys(value).launchIn(ctx.scope)
    }

    /**
     * Persists the HIBP API token. A blank token clears it; otherwise it must be
     * 32 hex characters. Invalid input is ignored (Swift validates first).
     */
    fun setHibpApiToken(token: String) {
        val put = watchtowerPutHibpApiToken ?: return
        val normalized = token.trim()
        if (normalized.isEmpty()) {
            put(null).launchIn(ctx.scope)
            return
        }
        if (HIBP_API_TOKEN_REGEX.matches(normalized)) {
            put(normalized).launchIn(ctx.scope)
        }
    }

    /** True if [token] is blank (clears the token) or a valid 32-hex-char token. */
    fun isValidHibpApiToken(token: String): Boolean {
        val normalized = token.trim()
        return normalized.isEmpty() || HIBP_API_TOKEN_REGEX.matches(normalized)
    }
}
