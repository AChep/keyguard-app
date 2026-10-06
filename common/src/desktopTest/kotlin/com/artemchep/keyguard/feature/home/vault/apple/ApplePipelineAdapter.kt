package com.artemchep.keyguard.feature.home.vault.apple

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.ScrollPositionState
import com.artemchep.keyguard.feature.home.vault.screen.VaultListPersistence
import com.artemchep.keyguard.feature.home.vault.screen.VaultListState
import com.artemchep.keyguard.feature.navigation.state.DiskHandle
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScopeZygote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import org.koin.core.scope.Scope

private fun VaultListState.Content.Items.orgChromeSettled(): Boolean = list
    .asSequence()
    .filterIsInstance<VaultItem2.Item>()
    .none { item ->
        item.source.organizationId != null &&
                item.feature !is VaultItem2.Item.Feature.Organization
    }

internal class DualVaultListHandle(
    val canonical: VaultListHandle,
    val apple: AppleSourceHandle,
    /** The shared producer scope; also the session's [translate] source. */
    val scope: RememberStateFlowScopeZygote,
    val koinScope: Scope,
) {
    suspend fun awaitStructureParity(
        description: String,
        timeout: Duration = VaultListHandle.DEFAULT_TIMEOUT,
        canonicalPredicate: (VaultListState) -> Boolean = { true },
    ): DualParitySnapshot = try {
        withTimeout(timeout) {
            combine(
                canonical.stateFlow,
                apple.stateFlow,
            ) { canonicalState, appleState ->
                canonicalState to appleState
            }
                .first { (canonicalState, appleState) ->
                    val items = canonicalState?.itemsOrNull()
                    canonicalState != null && items != null && appleState != null &&
                            canonicalPredicate(canonicalState) &&
                            items.orgChromeSettled() &&
                            items.toExpectedAppleEntries() == appleState.entryKeys()
                }
                .let { (canonicalState, appleState) ->
                    DualParitySnapshot(
                        canonical = canonicalState!!,
                        items = canonicalState.itemsOrNull()!!,
                        apple = appleState!!,
                    )
                }
        }
    } catch (e: TimeoutCancellationException) {
        val canonicalState = canonical.stateFlow.value
        val items = canonicalState?.itemsOrNull()
        val appleState = apple.stateFlow.value
        throw AssertionError(
            buildString {
                append("Timed out ($timeout) awaiting structure parity: $description.\n")
                append("Canonical: ")
                append(canonicalState?.let(::describeCanonical) ?: "no emission")
                append("\nApple: ")
                append(appleState?.let(::describe) ?: "no emission")
                if (items != null && appleState != null) {
                    append("\n")
                    append(
                        describeEntryDiff(
                            expected = items.toExpectedAppleEntries(),
                            actual = appleState.entryKeys(),
                        ),
                    )
                }
            },
            e,
        )
    }

    suspend fun reportScrollWithCurrentRevision(
        anchorId: String,
        offset: Int,
    ): Long {
        repeat(50) {
            val state = apple.stateFlow.value
                ?: throw AssertionError("reportScroll before the first Apple emission")
            apple.source.reportScroll(anchorId, offset, state.revision)
            val persisted = apple.scrollState()
            if (persisted.id == anchorId && persisted.offset == offset) {
                return state.revision
            }
            delay(50)
        }
        throw AssertionError(
            "reportScroll never accepted anchor '$anchorId'; " +
                    "persisted scroll state: ${apple.scrollState()}",
        )
    }
}

/** One converged canonical + Apple state pair; the unit every cell asserts on. */
internal class DualParitySnapshot(
    val canonical: VaultListState,
    val items: VaultListState.Content.Items,
    val apple: AppleVaultListState,
)

internal class AppleSourceHandle(
    val source: AppleVaultListSource,
    scope: CoroutineScope,
    private val scrollSink: MutableStateFlow<ScrollPositionState>,
) {
    val stateFlow = MutableStateFlow<AppleVaultListState?>(null)
    val headerFlow = MutableStateFlow<AppleVaultHeader?>(null)
    val filterCatalogFlow = MutableStateFlow<AppleVaultFilterCatalog?>(null)
    val filterStateFlow = MutableStateFlow<AppleVaultFilterState?>(null)
    val sortMenuFlow = MutableStateFlow<AppleVaultSortMenu?>(null)
    val toolbarFlow = MutableStateFlow<AppleVaultToolbar?>(null)
    val selectionFlow = MutableStateFlow<AppleVaultSelection?>(null)
    val totpTokensFlow = MutableStateFlow<List<Pair<String, TotpToken>>?>(null)

    init {
        source.state.onEach { stateFlow.value = it }.launchIn(scope)
        source.header.onEach { headerFlow.value = it }.launchIn(scope)
        source.filterCatalog.onEach { filterCatalogFlow.value = it }.launchIn(scope)
        source.filterState.onEach { filterStateFlow.value = it }.launchIn(scope)
        source.sortMenu.onEach { sortMenuFlow.value = it }.launchIn(scope)
        source.toolbar.onEach { toolbarFlow.value = it }.launchIn(scope)
        source.selection.onEach { selectionFlow.value = it }.launchIn(scope)
        source.totpTokens.onEach { totpTokensFlow.value = it }.launchIn(scope)
    }

    fun scrollState(): ScrollPositionState = scrollSink.value

    suspend fun awaitState(
        description: String = "a Apple state",
        timeout: Duration = VaultListHandle.DEFAULT_TIMEOUT,
        predicate: (AppleVaultListState) -> Boolean = { true },
    ): AppleVaultListState =
        stateFlow.awaitValue(timeout, description, ::describe, predicate)

    suspend fun awaitHeader(
        description: String = "a Apple header",
        timeout: Duration = VaultListHandle.DEFAULT_TIMEOUT,
        predicate: (AppleVaultHeader) -> Boolean = { true },
    ): AppleVaultHeader = headerFlow.awaitValue(timeout, description, { header ->
        "loaded=${header.loaded}, needsAccount=${header.needsAccount}, " +
                "query='${header.query}'@${header.queryRevision}, " +
                "suggestion='${header.qualifierSuggestion}', " +
                "paywalled=${header.paywalled}, " +
                "createActions=${header.createActions.map { it.id }}"
    }, predicate)

    suspend fun awaitFilterCatalog(
        description: String = "a Apple filter catalog",
        timeout: Duration = VaultListHandle.DEFAULT_TIMEOUT,
        predicate: (AppleVaultFilterCatalog) -> Boolean = { true },
    ): AppleVaultFilterCatalog = filterCatalogFlow.awaitValue(timeout, description, { catalog ->
        "revision=${catalog.revision}, groups=" +
                catalog.groups.map { "${it.sectionId}(collapsed=${it.collapsed}, n=${it.items.size})" }
    }, predicate)

    suspend fun awaitFilterState(
        description: String = "a Apple filter state",
        timeout: Duration = VaultListHandle.DEFAULT_TIMEOUT,
        predicate: (AppleVaultFilterState) -> Boolean = { true },
    ): AppleVaultFilterState = filterStateFlow.awaitValue(timeout, description, { state ->
        "rev=${state.filterRevision}, checked=${state.checkedIds}, " +
                "enabled=${state.enabledIds.size} ids, canClear=${state.canClear}"
    }, predicate)

    suspend fun awaitSortMenu(
        description: String = "a Apple sort menu",
        timeout: Duration = VaultListHandle.DEFAULT_TIMEOUT,
        predicate: (AppleVaultSortMenu) -> Boolean = { true },
    ): AppleVaultSortMenu = sortMenuFlow.awaitValue(timeout, description, { menu ->
        "visible=${menu.visible}, canClear=${menu.canClear}, items=" +
                menu.items.map { "${it.id}${if (it.checked) "*" else ""}" }
    }, predicate)

    suspend fun awaitToolbar(
        description: String = "a Apple toolbar",
        timeout: Duration = VaultListHandle.DEFAULT_TIMEOUT,
        predicate: (AppleVaultToolbar) -> Boolean = { true },
    ): AppleVaultToolbar = toolbarFlow.awaitValue(timeout, description, { toolbar ->
        "syncing=${toolbar.syncing}, actions=${toolbar.actions.map { it.id }}"
    }, predicate)

    suspend fun awaitSelection(
        description: String = "a Apple selection",
        timeout: Duration = VaultListHandle.DEFAULT_TIMEOUT,
        predicate: (AppleVaultSelection) -> Boolean = { true },
    ): AppleVaultSelection = selectionFlow.awaitValue(timeout, description, { selection ->
        "count=${selection.count}, actions=${selection.actions.map { it.id }}"
    }, predicate)
}

/**
 * Both pipelines share persisted flows, so they must also share their disk writer.
 * A second handle has no linked flows and can overwrite the first handle's saved state.
 */
private class SharedPersistenceScope(
    private val delegate: RememberStateFlowScopeZygote,
) : RememberStateFlowScopeZygote by delegate {
    private val diskHandleLock = Mutex()
    private val diskHandles = mutableMapOf<Pair<String, Boolean>, DiskHandle>()

    override suspend fun loadDiskHandle(key: String, global: Boolean): DiskHandle =
        diskHandleLock.withLock {
            diskHandles.getOrPut(key to global) {
                delegate.loadDiskHandle(key, global)
            }
        }
}

internal fun <T> VaultListTestHarness.runDual(
    args: VaultRoute.Args = VaultRoute.Args(),
    mode: AppMode = AppMode.Main,
    screenName: String = VaultListPersistence.SCREEN_COMPOSE,
    block: suspend DualVaultListHandle.() -> T,
): T = runBlocking {
    val appJob = SupervisorJob()
    val appScope = CoroutineScope(appJob + Dispatchers.Default)
    val screenScope = CoroutineScope(SupervisorJob(appJob) + Dispatchers.Default)
    val navigation = RecordingNavigationController(appScope)
    val messages = RecordingShowMessage()
    try {
        val scope = newVaultListTestScope(
            screenName = screenName,
            screenScope = screenScope,
            appScope = appScope,
            screenStateStore = screenStates,
            navigationController = navigation,
            showMessage = messages,
            clipboardService = clipboard,
        ).let(::SharedPersistenceScope)
        val (canonicalFlow, appleSource) = withContext(screenScope.coroutineContext) {
            val canonicalFlow = scope.CanonicalVaultListPipeline(koinScope, args, mode)
            val appleSource = scope.createAppleVaultListSource(koinScope, args, mode)
            canonicalFlow to appleSource
        }
        val canonicalSink = MutableStateFlow<VaultListState?>(null)
        canonicalFlow
            .onEach { canonicalSink.value = it }
            .launchIn(screenScope)
        val canonicalHandle = VaultListHandle(
            stateFlow = canonicalSink,
            navigation = navigation,
            messages = messages,
            clipboard = clipboard,
            persistence = screenStates,
            recorders = recorders,
            flows = flows,
        )
        val appleHandle = AppleSourceHandle(
            source = appleSource,
            scope = screenScope,
            // The scope registry hands back the SAME sink instance the
            // pipelines created for this key.
            scrollSink = scope.mutablePersistedFlow(
                VaultListPersistence.KEY_SCROLL_STATE,
            ) { ScrollPositionState() },
        )
        val handle = DualVaultListHandle(
            canonical = canonicalHandle,
            apple = appleHandle,
            scope = scope,
            koinScope = koinScope,
        )
        block(handle)
    } finally {
        screenScope.cancel()
        appScope.cancel()
        appJob.join()
    }
}

//
// Structure projections
//

/** The (id, kind) identity of one Apple list slot; what structure parity compares. */
internal data class EntryKey(
    val id: String,
    val kind: Int,
)

/** Projects the canonical item list into the Apple entry vocabulary. */
internal fun VaultListState.Content.Items.toExpectedAppleEntries(): List<EntryKey> =
    list.map { element ->
        when (element) {
            is VaultItem2.Item -> EntryKey(element.id, AppleVaultEntry.KIND_ITEM)
            is VaultItem2.Section -> EntryKey("section." + element.id, AppleVaultEntry.KIND_SECTION)
            is VaultItem2.NoItems -> EntryKey(element.id, AppleVaultEntry.KIND_NO_ITEMS)
            is VaultItem2.NoSuggestions -> EntryKey(element.id, AppleVaultEntry.KIND_NO_SUGGESTIONS)
            is VaultItem2.QuickFilters -> EntryKey(element.id, AppleVaultEntry.KIND_QUICK_FILTERS)
            is VaultItem2.Button -> throw AssertionError(
                "Unexpected Button row '${element.id}' in the canonical vault list",
            )
        }
    }

internal fun AppleVaultListState.entryKeys(): List<EntryKey> =
    entries.map { EntryKey(it.id, it.kind) }

//
// Diagnostics
//

private suspend fun <T : Any> StateFlow<T?>.awaitValue(
    timeout: Duration,
    description: String,
    describe: (T) -> String,
    predicate: (T) -> Boolean,
): T = try {
    withTimeout(timeout) {
        filterNotNull().first(predicate)
    }
} catch (e: TimeoutCancellationException) {
    val last = value
    throw AssertionError(
        "Timed out ($timeout) awaiting $description; " +
                "last value: ${last?.let(describe) ?: "no emission"}",
        e,
    )
}

internal fun describe(state: AppleVaultListState): String {
    val ids = state.entries.map { it.id }
    val preview = if (ids.size <= 24) ids else ids.take(24) + "… +${ids.size - 24}"
    return "revision=${state.revision}, itemCount=${state.itemCount}, " +
            "entries(${state.entries.size})=$preview"
}

private fun describeCanonical(state: VaultListState): String {
    val items = state.itemsOrNull()
        ?: return "revision=${state.revision}, query='${state.query.text}', " +
                "content=${state.content::class.simpleName}"
    val ids = items.list.map { it.id }
    val preview = if (ids.size <= 24) ids else ids.take(24) + "… +${ids.size - 24}"
    return "revision=${state.revision}, query='${state.query.text}', " +
            "count=${items.count}, list(${items.list.size})=$preview"
}

/** A first-mismatch diff of two entry-key lists, for readable failures. */
internal fun describeEntryDiff(
    expected: List<EntryKey>,
    actual: List<EntryKey>,
): String {
    if (expected == actual) {
        return "entry lists are equal"
    }
    val index = expected.zip(actual)
        .indexOfFirst { (a, b) -> a != b }
        .takeIf { it >= 0 }
        ?: minOf(expected.size, actual.size)
    fun slice(list: List<EntryKey>) = list
        .drop((index - 2).coerceAtLeast(0))
        .take(5)
    return "first entry mismatch at index $index " +
            "(expected size ${expected.size}, actual size ${actual.size}); " +
            "expected …${slice(expected)}…, actual …${slice(actual)}…"
}
