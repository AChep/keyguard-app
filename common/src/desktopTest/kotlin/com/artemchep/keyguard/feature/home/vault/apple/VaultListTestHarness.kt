package com.artemchep.keyguard.feature.home.vault.apple

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.di.VaultSessionScope
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.home.vault.model.SortItem
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.VaultListPersistence
import com.artemchep.keyguard.feature.home.vault.screen.VaultListState
import com.artemchep.keyguard.feature.home.vault.screen.vaultListScreenStateProducer
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope

internal typealias VaultListPipeline = suspend RememberStateFlowScope.(
    Scope,
    VaultRoute.Args,
    AppMode,
) -> Flow<VaultListState>

/** The canonical Compose-parity pipeline, [vaultListScreenStateProducer]. */
internal val CanonicalVaultListPipeline: VaultListPipeline = { koinScope, args, mode ->
    with(koinScope) {
        vaultListScreenStateProducer(
            filterContext = get(),
            addCipherFilter = get(),
            confirmationRouteFactory = get(),
            getCipherFilters = get(),
            args = args,
            highlightBackgroundColor = VaultListTestHarness.HIGHLIGHT_BACKGROUND,
            highlightContentColor = VaultListTestHarness.HIGHLIGHT_CONTENT,
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
}

internal class VaultListTestHarness(
    fixtures: VaultListFixtures = VaultListFixtures.benchmarkSlice(),
    overrides: Module.() -> Unit = {},
) {
    companion object {
        // Distinctive colors so highlight assertions cannot confuse
        // them with any real theme color.
        val HIGHLIGHT_BACKGROUND = Color(0xFF123456)
        val HIGHLIGHT_CONTENT = Color(0xFF654321)
    }

    val flows = VaultListFixtureFlows(fixtures)
    val recorders = VaultListDiRecorders()
    val clipboard = RecordingClipboardService()
    val screenStates = InMemoryScreenStateStore()
    val koin: Koin = vaultListTestKoin(
        flows = flows,
        recorders = recorders,
        clipboardService = clipboard,
        overrides = overrides,
    )

    /** Stands in for the unlocked vault's session scope; every fake is a root definition. */
    val koinScope: Scope = koin.createScope(Uuid.random().toString(), named<VaultSessionScope>())

    val deeplinkService: DeeplinkService by lazy {
        koin.get()
    }

    fun <T> run(
        args: VaultRoute.Args = VaultRoute.Args(),
        mode: AppMode = AppMode.Main,
        screenName: String = VaultListPersistence.SCREEN_COMPOSE,
        pipeline: VaultListPipeline = CanonicalVaultListPipeline,
        block: suspend VaultListHandle.() -> T,
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
            )
            // Producers must never run their pipelines on the main
            // thread; mirror the production screen scope dispatcher.
            val stateFlow = withContext(screenScope.coroutineContext) {
                scope.pipeline(koinScope, args, mode)
            }
            val stateSink = MutableStateFlow<VaultListState?>(null)
            stateFlow
                .onEach { stateSink.value = it }
                .launchIn(screenScope)
            val handle = VaultListHandle(
                stateFlow = stateSink,
                navigation = navigation,
                messages = messages,
                clipboard = clipboard,
                persistence = screenStates,
                recorders = recorders,
                flows = flows,
            )
            block(handle)
        } finally {
            screenScope.cancel()
            appScope.cancel()
        }
    }
}

internal class VaultListHandle(
    val stateFlow: StateFlow<VaultListState?>,
    val navigation: RecordingNavigationController,
    val messages: RecordingShowMessage,
    val clipboard: RecordingClipboardService,
    val persistence: InMemoryScreenStateStore,
    val recorders: VaultListDiRecorders,
    val flows: VaultListFixtureFlows,
) {
    companion object {
        val DEFAULT_TIMEOUT = 30.seconds
    }

    /** The latest emission, `null` until the first one lands. */
    val state: VaultListState? get() = stateFlow.value

    suspend fun awaitState(
        timeout: Duration = DEFAULT_TIMEOUT,
        description: String = "state predicate",
        predicate: (VaultListState) -> Boolean = { true },
    ): VaultListState = try {
        withTimeout(timeout) {
            stateFlow
                .filterNotNull()
                .first(predicate)
        }
    } catch (e: TimeoutCancellationException) {
        val last = stateFlow.value
        throw AssertionError(
            "Timed out ($timeout) awaiting $description; " +
                    "last state: ${last?.let(::describe) ?: "no emission"}",
            e,
        )
    }

    suspend fun awaitItems(
        timeout: Duration = DEFAULT_TIMEOUT,
        description: String = "items predicate",
        predicate: (VaultListState.Content.Items) -> Boolean = { true },
    ): VaultListState.Content.Items = awaitState(
        timeout = timeout,
        description = description,
    ) { state ->
        val items = state.content as? VaultListState.Content.Items
        items != null && predicate(items)
    }.content as VaultListState.Content.Items

    suspend fun setQuery(text: String) {
        val state = awaitState(description = "a state with an editable query") {
            it.query.onSetText != null
        }
        state.query.onSetText!!.invoke(text)
    }

    suspend fun toggleFilterChip(
        sectionId: String,
        description: String = "a filter chip in section '$sectionId'",
        predicate: (FilterItem.ChipItem) -> Boolean = { true },
    ): FilterItem.ChipItem {
        val state = awaitState(description = description) { state ->
            state.filterChips(sectionId).any { it.onClick != null && predicate(it) }
        }
        val chip = state.filterChips(sectionId)
            .first { it.onClick != null && predicate(it) }
        chip.onClick!!.invoke()
        return chip
    }

    /** Selects a sort menu entry by its stable id, e.g. `"modify_date"`. */
    suspend fun selectSort(id: String): SortItem.Item {
        val state = awaitState(description = "a sort item with id '$id'") { state ->
            state.sortItems().any { it.id == id && it.onClick != null }
        }
        val item = state.sortItems()
            .first { it.id == id && it.onClick != null }
        item.onClick!!.invoke()
        return item
    }

    suspend fun awaitPersisted(
        diskKey: String,
        timeout: Duration = DEFAULT_TIMEOUT,
        predicate: (Map<String, Any?>) -> Boolean,
    ): Map<String, Any?> = try {
        withTimeout(timeout) {
            persistence.await(diskKey, predicate)
        }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError(
            "Timed out ($timeout) awaiting persisted state at '$diskKey'; " +
                    "current entries: ${persistence.snapshot().keys}",
            e,
        )
    }

    private fun describe(state: VaultListState): String {
        val content = state.content
        val summary = when (content) {
            is VaultListState.Content.Skeleton -> "Skeleton"
            is VaultListState.Content.AddAccount -> "AddAccount"
            is VaultListState.Content.Items ->
                "Items(count=${content.count}, ids=${content.cipherItems().map { it.id }})"

            else -> content.toString()
        }
        return "revision=${state.revision}, query='${state.query.text}', " +
                "filters=${state.filters.size}, sort=${state.sort.size}, content=$summary"
    }
}

//
// Assertion-friendly projections
//

internal fun VaultListState.itemsOrNull(): VaultListState.Content.Items? =
    content as? VaultListState.Content.Items

internal fun VaultListState.Content.Items.cipherItems(): List<VaultItem2.Item> =
    list.filterIsInstance<VaultItem2.Item>()

internal fun VaultListState.Content.Items.cipherIds(): List<String> =
    cipherItems().map { it.id }

internal fun VaultListState.filterChips(
    sectionId: String? = null,
): List<FilterItem.ChipItem> = filters
    .filterIsInstance<FilterItem.ChipItem>()
    .filter { sectionId == null || it.sectionId == sectionId }

internal fun VaultListState.sortItems(): List<SortItem.Item> = sort
    .filterIsInstance<SortItem.Item>()
