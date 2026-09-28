package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.duplicates.DuplicatesRoute
import com.artemchep.keyguard.feature.duplicates.list.DuplicatesListState
import com.artemchep.keyguard.feature.duplicates.list.duplicatesListStateProducer
import com.artemchep.keyguard.feature.home.vault.apple.currentSelectableItemState
import com.artemchep.keyguard.feature.home.vault.apple.localStateChanges
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRoute
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultRowContent
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultListState
import com.artemchep.keyguard.feature.home.vault.apple.assembleSiblingAppleVaultState
import com.artemchep.keyguard.feature.home.vault.apple.buildSiblingAppleActionDescriptors
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScopeImpl
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.model.vaultItemFingerprint
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.core.scope.Scope

/** ObjC-exported Duplicates session backed by [duplicatesListStateProducer]. */
class DuplicatesSession internal constructor(
    private val ctx: CoreContext,
    private val filter: DFilter?,
    /**
     * Provides the navigation interceptor (stack + dialog composition, see
     * `NavigationStackController.interceptor`) for the unlocked session's sub-DI:
     * the per-group "Merge" action and the multi-selection bulk actions
     * (rename / trash / send / merge / …) reach a real dialog / sheet through it,
     * and a cipher open routes a `VaultViewRoute` intent through it. `null` = those
     * are dropped (loudly).
     */
    private val navigationInterceptorProvider: ((Scope) -> ((NavigationIntent) -> Boolean))?,
) {
    /** The live producer state + the scope/interceptor its commands need. */
    private class Active(
        val state: DuplicatesListState,
        /**
     * The producer's [com.artemchep.keyguard.feature.navigation.state.TranslatorScope]
     * for building selection descriptors.
     */
        val scope: RememberStateFlowScopeImpl,
        val interceptor: ((NavigationIntent) -> Boolean)?,
    )

    /** `null` while the vault is locked (or after [close]). */
    private val activeState = MutableStateFlow<Active?>(null)

    /** Every not-yet-cancelled channel, so [close] tears the whole session down. */
    private val channels = mutableListOf<KeyguardCancellable>()

    /** The state-anchored delta publisher (full-frame diff + lock-reset). */
    private val framePublisher = VaultListFramePublisher()

    /**
     * The master gate: rebuilds the headless producer on every unlock, clears it
     * on every lock, and keeps the producer flow collected so the per-item
     * selection flows stay alive.
     */
    private val master: KeyguardCancellable = ctx.launchSessionObserver(
        onLocked = {
            activeState.value = null
        },
    ) { state ->
        val interceptor = navigationInterceptorProvider?.invoke(state.sessionKoin)
        val producerScope = ctx.koin.newHeadlessStateFlowScope(
            key = "duplicates",
            scope = this,
            navigationInterceptor = interceptor,
        )
        val producerFlow = with(state.sessionKoin) {
            producerScope.duplicatesListStateProducer(
                filterContext = get(),
                args = DuplicatesRoute.Args(filter = filter),
                clipboardService = get(),
                getTotpCode = get(),
                getConcealFields = get(),
                getAppIcons = get(),
                getWebsiteIcons = get(),
                getOrganizations = get(),
                getCollections = get(),
                getCiphers = get(),
                getProfiles = get(),
                getCanWrite = get(),
                cipherToolbox = get(),
                cipherDuplicatesCheck = get(),
                confirmationRouteFactory = get(),
            )
        }
        // Collecting keeps this session block alive (the flow never completes);
        // each Loadable.Ok republishes the live state to the channels.
        producerFlow.collect { loadable ->
            val listState = loadable.getOrNull()
            activeState.value = listState?.let { Active(it, producerScope, interceptor) }
        }
    }

    //
    // The list channel. Callback OFF-MAIN by design (like the vault list).
    //

    /**
     * The Duplicates item / section / merge-button rows as [VaultListDelta]s,
     * delivered on a BACKGROUND thread by design — the SAME contract as
     * `VaultListSession.observeListDelta` / `RecentsController.observeRecentsListDelta`.
     *
     * The producer's flat `List<VaultItem2>` (already reflecting grouping + the
     * per-group merge buttons + the blank section separators) is projected through
     * the shared [assembleSiblingAppleVaultState]. Because the producer does NOT re-emit
     * the outer state on a selection toggle (selection rides each item's own
     * `localStateFlow`), the projection re-runs whenever ANY per-item selectable
     * state changes ([combine] over the item rows' local flows), folding the
     * group-scoped selection into the row flags + fingerprint so a toggle re-diffs
     * exactly the affected rows.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeListDelta(
        onChange: (VaultListDelta) -> Unit,
    ): KeyguardCancellable = track(
        ctx.launchObserver {
            var revisionCounter = 0L
            val frames: Flow<AppleVaultListState?> = activeState.flatMapLatest { active ->
                if (active == null) {
                    flowOf<AppleVaultListState?>(null)
                } else {
                    val itemRows = active.state.items.filterIsInstance<VaultItem2.Item>()
                    // Re-emit whenever any per-item selectable state changes; the
                    // selecting/selected values are re-read fresh inside the
                    // projection off each item's `localStateFlow.value`.
                    val selectionTrigger: Flow<Unit> =
                        if (itemRows.isEmpty()) {
                            flowOf(Unit)
                        } else {
                            combine(itemRows.map { it.localStateChanges() }) { }
                        }
                    selectionTrigger
                        .map {
                            assembleSiblingAppleVaultState(
                                revision = ++revisionCounter,
                                items = active.state.items,
                                mode = AppMode.Main,
                                fingerprintOf = ::vaultItemFingerprint,
                                selectionOf = ::duplicatesSelectionFlags,
                            )
                        }
                        .throttleLatest()
                }
            }
            framePublisher.run(frames, onChange)
        },
    )

    //
    // The selection channel. Callback on Main.
    //

    /**
     * The active multi-selection (count + bulk actions) as a small
     * [VaultSessionSelectionSnapshot] pushed on Main; `count == 0` = inactive. The
     * producer's `Selection.actions` ([ContextItem]s) are projected into the
     * pure-data descriptor vocabulary through the SHARED
     * [buildSiblingAppleActionDescriptors], then symbol-filled by [toSnapshot].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSelection(
        onChange: (VaultSessionSelectionSnapshot) -> Unit,
    ): KeyguardCancellable = track(
        ctx.launchObserver {
            activeState.collectLatest { active ->
                if (active == null) {
                    ctx.publishOnMain { onChange(VaultSessionSelectionSnapshot.empty) }
                    return@collectLatest
                }
                active.state.selectionStateFlow
                    .map { selection ->
                        if (selection == null) {
                            VaultSessionSelectionSnapshot.empty
                        } else {
                            val descriptors = active.scope.buildSiblingAppleActionDescriptors(
                                items = selection.actions,
                                surface = "duplicates",
                            )
                            VaultSessionSelectionSnapshot(
                                count = selection.count,
                                actions = descriptors.map { it.toSnapshot() },
                                selectedIds = selection.selectedIds.sorted(),
                            )
                        }
                    }
                    .distinctUntilChanged()
                    .collectOnMain { onChange(it) }
            }
        },
    )

    //
    // Commands: thin delegates resolved off the live producer state. Safe from
    // any thread; silent no-ops while the vault is locked.
    //

    /**
     * Toggles the row's selection membership through the producer's own
     * group-scoped handle: a tap while selecting (`onClick`) or the long-press /
     * "Select" begin (`onLongClick`). A cross-group toggle resolves to a `null`
     * handle Kotlin-side and is a no-op — the group-scoping is preserved.
     */
    fun toggleSelection(rowId: String) {
        val item = itemFor(rowId) ?: return
        val selectable = item.currentSelectableItemState()
        (selectable.onClick ?: selectable.onLongClick)?.invoke()
    }

    /** Invokes one of the active multi-selection's bulk actions by its `FlatItemAction.id`. */
    fun invokeSelectionAction(id: String) {
        invokeSelectionAction(id, expectedSelectedIds = null)
    }

    fun invokeSelectionActionForItems(id: String, itemIds: List<String>) {
        invokeSelectionAction(id, expectedSelectedIds = itemIds.toSet())
    }

    private fun invokeSelectionAction(id: String, expectedSelectedIds: Set<String>?) {
        val selection = activeState.value?.state?.selectionStateFlow?.value ?: return
        if (expectedSelectedIds != null && selection.selectedIds != expectedSelectedIds) return
        selection.actions
            .filterIsInstance<FlatItemAction>()
            .firstOrNull { it.id == id }
            ?.onClick
            ?.invoke()
    }

    /** Clears the active multi-selection (the bulk bar's X). */
    fun clearSelection() {
        activeState.value?.state?.selectionStateFlow?.value?.onClear?.invoke()
    }

    /**
     * Dispatches a per-row action. The only one this surface carries is the
     * per-group "Merge" button ([rowId] == the button entry id): its producer
     * closure opens the native merged-cipher edit sheet through the interceptor.
     */
    fun performVaultRowAction(rowId: String, actionId: String) {
        val button = activeState.value?.state?.items
            ?.filterIsInstance<VaultItem2.Button>()
            ?.firstOrNull { it.id == rowId }
        if (button != null) {
            button.onClick?.invoke()
            return
        }
        println("[Keyguard][duplicates] unknown row action '$actionId' for row '$rowId' — dropped")
    }

    /** Opens a duplicate row through the shared navigation interceptor. */
    fun openVaultRow(rowId: String) {
        val active = activeState.value ?: return
        val item = itemFor(rowId) ?: return
        val route = VaultViewRoute(
            itemId = item.source.id,
            accountId = item.accountId,
        )
        val handled = active.interceptor?.invoke(NavigationIntent.NavigateToRoute(route)) == true
        if (!handled) {
            println(
                "[Keyguard][duplicates] cipher open '$rowId' DROPPED — " +
                                "no interceptor claimed the VaultViewRoute intent!",
            )
        }
    }

    /**
     * Tears the session down: the master gate (and with it the headless producer)
     * plus every channel handed out by `observe*`. Idempotent.
     */
    fun close() {
        master.cancel()
        channels.forEach { it.cancel() }
        channels.clear()
        activeState.value = null
    }

    //
    // Internals.
    //

    private fun itemFor(rowId: String): VaultItem2.Item? =
        activeState.value?.state?.items
            ?.filterIsInstance<VaultItem2.Item>()
            ?.firstOrNull { it.id == rowId }

    /** The per-row selection display bits, read fresh off the item's local flow. */
    private fun duplicatesSelectionFlags(item: VaultItem2.Item): Int {
        val selectable = item.currentSelectableItemState()
        var flags = 0
        if (selectable.selecting) flags = flags or AppleVaultRowContent.FLAG_SELECTING
        if (selectable.selected) flags = flags or AppleVaultRowContent.FLAG_SELECTED
        return flags
    }

    private fun track(cancellable: KeyguardCancellable): KeyguardCancellable {
        channels += cancellable
        return cancellable
    }
}
