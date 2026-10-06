package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.customfilters_header_title
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRoute
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultListSource
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultListState
import com.artemchep.keyguard.feature.home.vault.apple.createAppleVaultListSource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.ListNavigationOrigin
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.model.TotpFieldSnapshot
import com.artemchep.keyguard.apple.model.totpMapFlow
import com.artemchep.keyguard.apple.throttleLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.scope.Scope

/** Wraps [AppleVaultListSource]; one per visible list. */
class VaultListSession internal constructor(
    private val ctx: CoreContext,
    private val args: VaultRoute.Args,
    private val persistenceScope: String,
    /** `null` = row opens and route-firing toolbar actions are dropped (loudly). */
    private val navigationInterceptorProvider: ((Scope) -> ((NavigationIntent) -> Boolean))?,
    /**
     * Non-null = a custom cipher-filter tab: the filter is resolved per unlock and its tab args
     * replace [args]. A filter that no longer exists keeps the session empty; the navigation
     * snapshot drops its tab in the same emission, so nothing renders it.
     */
    private val cipherFilterId: String? = null,
    private val openListCipher: (ListNavigationOrigin, String, String) -> Unit,
) {
    private val navigationOrigin = MutableStateFlow<ListNavigationOrigin?>(null)

    fun setNavigationOrigin(origin: ListNavigationOrigin?) {
        navigationOrigin.value = origin
    }

    private class ActiveSource(
        val source: AppleVaultListSource,
        val getTotpCode: GetTotpCodeWithOffset,
    )

    /** `null` while the vault is locked (or after [close]). */
    private val activeState = MutableStateFlow<ActiveSource?>(null)

    /**
     * Every not-yet-cancelled channel handed out by `observe*`, so [close]
     * tears the whole session down even if Swift forgot individual
     * cancellables. Main-confined: `observe*` and [close] are called from the
     * main thread.
     */
    private val channels = mutableListOf<KeyguardCancellable>()

    private val framePublisher = VaultListFramePublisher()

    private val master: KeyguardCancellable = ctx.launchSessionObserver(
        onLocked = {
            activeState.value = null
        },
    ) { state ->
        val effectiveArgs = if (cipherFilterId != null) {
            resolveCipherFilterArgs(state.sessionKoin, cipherFilterId)
                ?: return@launchSessionObserver
        } else {
            args
        }
        val interceptor = navigationInterceptorProvider?.invoke(state.sessionKoin)
        val scope = ctx.koin.newHeadlessStateFlowScope(
            name = persistenceScope,
            scope = this,
            navigationInterceptor = interceptor,
        )
        val source = scope.createAppleVaultListSource(
            sessionKoin = state.sessionKoin,
            args = effectiveArgs,
            mode = AppMode.Main,
        )
        activeState.value = ActiveSource(
            source = source,
            getTotpCode = state.sessionKoin.get(),
        )
        source.cipherOpenEvents.collect { secret ->
            val origin = navigationOrigin.value
            if (origin != null) {
                openListCipher(origin, secret.id, secret.accountId)
                return@collect
            }
            val route = VaultViewRoute(
                itemId = secret.id,
                accountId = secret.accountId,
            )
            val intent = NavigationIntent.NavigateToRoute(route)
            val handled = interceptor?.invoke(intent) == true
            if (!handled) {
                println(
                    "[Keyguard][vaultList] cipher open '${secret.id}' DROPPED — " +
                            "no navigation interceptor claimed the VaultViewRoute intent!",
                )
            }
        }
    }

    /**
     * WARNING — unlike every other bridge channel, [onChange] is invoked on a
     * BACKGROUND thread by design: the Swift side converts the (potentially
     * large) delta off-main and hops to Main itself. Do not touch UI state in
     * the callback directly.
     *
     * Delivery is state-anchored: bursts coalesce to one flush per ~48ms (first
     * immediately), and each flush diffs the LATEST pipeline state against the
     * last DELIVERED one, so coalescing can never drop a change. A no-change diff
     * publishes nothing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeListDelta(
        onChange: (VaultListDelta) -> Unit,
    ): KeyguardCancellable = track(
        ctx.launchObserver {
            // Transitions are only null<->active, so the `flatMapLatest` cancel-on-switch
            // cannot reorder frames of two active sources.
            val frames: Flow<AppleVaultListState?> = activeState.flatMapLatest { active ->
                if (active == null) {
                    flowOf<AppleVaultListState?>(null)
                } else {
                    active.source.state.throttleLatest()
                }
            }
            framePublisher.run(frames, onChange)
        },
    )

    // The small channels. Callbacks on Main.

    fun observeHeader(
        onChange: (VaultSessionHeaderSnapshot) -> Unit,
    ): KeyguardCancellable = observeChannel(
        empty = VaultSessionHeaderSnapshot.empty,
        flowOf = { source -> source.header.map { it.toSnapshot() } },
        onChange = onChange,
    )

    fun observeFilterCatalog(
        onChange: (VaultFilterCatalogSnapshot) -> Unit,
    ): KeyguardCancellable = observeChannel(
        empty = VaultFilterCatalogSnapshot.empty,
        flowOf = { source -> source.filterCatalog.map { it.toSnapshot() } },
        onChange = onChange,
    )

    fun observeFilterState(
        onChange: (VaultFilterStateSnapshot) -> Unit,
    ): KeyguardCancellable = observeChannel(
        empty = VaultFilterStateSnapshot.empty,
        flowOf = { source -> source.filterState.map { it.toSnapshot() } },
        onChange = onChange,
    )

    fun observeSort(
        onChange: (VaultSessionSortSnapshot) -> Unit,
    ): KeyguardCancellable = observeChannel(
        empty = VaultSessionSortSnapshot.empty,
        flowOf = { source -> source.sortMenu.map { it.toSnapshot() } },
        onChange = onChange,
    )

    fun observeToolbar(
        onChange: (VaultSessionToolbarSnapshot) -> Unit,
    ): KeyguardCancellable = observeChannel(
        empty = VaultSessionToolbarSnapshot.empty,
        flowOf = { source -> source.toolbar.map { it.toSnapshot() } },
        onChange = onChange,
    )

    fun observeSelection(
        onChange: (VaultSessionSelectionSnapshot) -> Unit,
    ): KeyguardCancellable = observeChannel(
        empty = VaultSessionSelectionSnapshot.empty,
        flowOf = { source -> source.selection.map { it.toSnapshot() } },
        onChange = onChange,
    )

    /** Publishes visible-row TOTP codes once per second; empty while locked. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTotp(
        onChange: (Map<String, TotpFieldSnapshot>) -> Unit,
    ): KeyguardCancellable = track(
        ctx.launchObserver {
            activeState.collectLatest { active ->
                if (active == null) {
                    ctx.publishOnMain { onChange(emptyMap()) }
                    return@collectLatest
                }
                active.source.totpTokens
                    .flatMapLatest { tokens -> totpMapFlow(active.getTotpCode, tokens) }
                    .collectOnMain { onChange(it) }
            }
        },
    )

    private fun <T> observeChannel(
        empty: T,
        flowOf: (AppleVaultListSource) -> Flow<T>,
        onChange: (T) -> Unit,
    ): KeyguardCancellable = track(
        ctx.launchObserver {
            activeState.collectLatest { active ->
                if (active == null) {
                    ctx.publishOnMain { onChange(empty) }
                    return@collectLatest
                }
                flowOf(active.source)
                    .distinctUntilChanged()
                    .throttleLatest()
                    .collectOnMain { onChange(it) }
            }
        },
    )

    private fun track(cancellable: KeyguardCancellable): KeyguardCancellable {
        channels += cancellable
        return cancellable
    }

    // Commands delegate to the source and are safe from any thread.

    private val source: AppleVaultListSource?
        get() = activeState.value?.source

    fun setQuery(text: String) {
        source?.setQuery(text)
    }

    fun clearQuery() {
        source?.clearQuery()
    }

    fun applyQualifierSuggestion() {
        source?.applyQualifierSuggestion()
    }

    fun invokeFilter(id: String) {
        source?.invokeFilter(id)
    }

    fun clearFilters() {
        source?.clearFilters()
    }

    fun saveFilters() {
        source?.saveFilters()
    }

    fun toggleFilterSection(sectionId: String) {
        source?.toggleFilterSection(sectionId)
    }

    fun invokeSort(id: String) {
        source?.invokeSort(id)
    }

    fun clearSort() {
        source?.clearSort()
    }

    fun invokeToolbarAction(id: String) {
        source?.invokeToolbarAction(id)
    }

    fun invokeSelectionAction(id: String) {
        source?.invokeSelectionAction(id)
    }

    /** Context menus must not retarget an action after the displayed selection changes. */
    fun invokeSelectionActionForItems(id: String, itemIds: List<String>) {
        source?.invokeSelectionAction(id, expectedSelectedIds = itemIds.toSet())
    }

    fun toggleSelection(rowId: String) {
        source?.toggleSelection(rowId)
    }

    fun clearSelection() {
        source?.clearSelection()
    }

    fun setOpenedRow(rowId: String) {
        source?.setOpenedRow(rowId)
    }

    /** Opens the cipher through the navigation interceptor; dropped (loudly) when none claims it. */
    fun openVaultRow(rowId: String) {
        source?.openVaultRow(rowId)
    }

    /**
     * Fetched on demand, never carried in state. [callback] runs on the MAIN thread;
     * an unknown row (or a locked vault) yields an empty list.
     */
    fun rowActions(
        rowId: String,
        callback: (List<VaultActionDescriptorSnapshot>) -> Unit,
    ) {
        val active = activeState.value
        if (active == null) {
            callback(emptyList())
            return
        }
        ctx.backgroundScope.launch {
            val actions = active.source
                .rowActions(rowId)
                .map { it.toSnapshot() }
            ctx.publishOnMain {
                callback(actions)
            }
        }
    }

    fun performVaultRowAction(rowId: String, actionId: String) {
        source?.performVaultRowAction(rowId, actionId)
    }

    fun performVaultBadgeTap(rowId: String, badgeId: String) {
        source?.performVaultBadgeTap(rowId, badgeId)
    }

    fun createItem(actionId: String) {
        source?.createItem(actionId)
    }

    /**
     * [structureRevision] must be the [VaultListDelta.revision] of the frame the client
     * is rendering; a report against a stale frame is ignored.
     */
    fun reportScroll(anchorId: String, offset: Int, structureRevision: Long) {
        source?.reportScroll(anchorId, offset, structureRevision)
    }

    /** Tears down the headless source and every channel handed out by `observe*`. Idempotent. */
    fun close() {
        master.cancel()
        channels.forEach { it.cancel() }
        channels.clear()
        activeState.value = null
    }
}

/**
 * The canonical cipher-filter tab args, mirroring the shared
 * `createCipherFilterHomeNavigationItem` exactly; `null` when the filter no
 * longer exists.
 */
private suspend fun resolveCipherFilterArgs(
    sessionKoin: Scope,
    cipherFilterId: String,
): VaultRoute.Args? {
    val getCipherFilters = sessionKoin.get<GetCipherFilters>()
    val filter = getCipherFilters().first()
        .firstOrNull { it.id == cipherFilterId }
        ?: run {
            println(
                "[Keyguard][vaultList] cipher filter '$cipherFilterId' not found — " +
                        "the filter tab stays empty until the filter reappears.",
            )
            return null
        }
    return VaultRoute.Args(
        appBar = VaultRoute.Args.AppBar(
            title = filter.name,
            subtitle = TextHolder.Res(Res.string.customfilters_header_title),
        ),
        filter = FilterHolder(filter.filter).filter,
        preselect = false,
        canAddSecrets = false,
    )
}
