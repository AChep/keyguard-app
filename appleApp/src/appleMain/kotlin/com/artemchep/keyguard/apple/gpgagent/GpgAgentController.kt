package com.artemchep.keyguard.apple.gpgagent

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.apple.model.SettingOptionSnapshot
import com.artemchep.keyguard.apple.model.mapFilterItemsToSnapshots
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.seedHex
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentStatusService
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestProcessorImpl
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgent
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindowVariants
import com.artemchep.keyguard.common.usecase.GetGpgAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.PutGpgAgent
import com.artemchep.keyguard.common.usecase.PutGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.PutGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.PutGpgAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.RemoveGpgUsageHistory
import com.artemchep.keyguard.feature.gpgagent.filter.GpgAgentFiltersState
import com.artemchep.keyguard.feature.gpgagent.filter.gpgAgentFiltersStateProducer
import com.artemchep.keyguard.feature.gpgagent.help.macosSandboxGpgAgentSetupCommand
import com.artemchep.keyguard.feature.gpgagent.history.GpgAgentHistoryItem
import com.artemchep.keyguard.feature.gpgagent.history.GpgAgentHistoryState
import com.artemchep.keyguard.feature.gpgagent.history.gpgAgentHistoryStateProducer
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.format
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Main-app worker; construction alone never launches a helper in AutoFill. */
internal class GpgAgentController(
    private val ctx: CoreContext,
    private val runtime: GpgAgentRuntime = createGpgAgentRuntime(),
) {
    private val getEnabled: GetGpgAgent by lazy { ctx.koin.get() }
    private val putEnabled: PutGpgAgent by lazy { ctx.koin.get() }
    private val getApprovalWindow: GetGpgAgentApprovalWindow by lazy { ctx.koin.get() }
    private val getApprovalWindowVariants: GetGpgAgentApprovalWindowVariants by lazy { ctx.koin.get() }
    private val getApprovalCachePolicy: GetGpgAgentApprovalCachePolicy by lazy { ctx.koin.get() }
    private val getFilter: GetGpgAgentFilter by lazy { ctx.koin.get() }
    private val requests = GpgAgentApprovalQueue()
    private val status = MutableStateFlow(GpgAgentStatusSnapshot.empty)
    private val retry = MutableStateFlow(0L)
    private val lifecycleMutex = Mutex()
    private var applier: Job? = null
    private var handle: GpgAgentRuntimeHandle? = null
    private var runScope: CoroutineScope? = null
    private var generation = 0L
    private var latestApprovalWindowVariants: List<Duration> = emptyList()
    private var latestGpgAgentFiltersState: GpgAgentFiltersState? = null
    private var filterObservationGeneration = 0L
    private var gpgAgentFilterHandlers: Map<String, () -> Unit> = emptyMap()

    fun startGpgAgentApplier() {
        if (applier != null) return
        applier = ctx.scope.launch {
            try {
                combine(getEnabled().distinctUntilChanged(), retry) { enabled, _ -> enabled }
                    .collectLatest { enabled ->
                        lifecycleMutex.withLock {
                            stop()
                            if (enabled) start()
                        }
                    }
            } finally {
                withContext(NonCancellable) {
                    lifecycleMutex.withLock { stop() }
                }
            }
        }
    }

    fun setGpgAgentEnabled(value: Boolean) {
        putEnabled(value).launchIn(ctx.scope)
    }

    fun retryGpgAgent() {
        retry.update { it + 1 }
    }

    private suspend fun start() {
        if (!runtime.isBinaryAvailable) {
            setStatus(GpgAgentRunState.UNSUPPORTED)
            return
        }
        setStatus(GpgAgentRunState.STARTING)
        val scope = CoroutineScope(
            ctx.backgroundScope.coroutineContext + SupervisorJob(ctx.scope.coroutineContext[Job]),
        )
        runScope = scope
        val thisGeneration = ++generation
        try {
            val di = ctx.koin
            val cryptoGenerator = di.get<CryptoGenerator>()
            val logger = di.get<LogRepository>()
            val processor = GpgAgentRequestProcessorImpl(
                logRepository = logger,
                crypto = di.get(),
                getVaultSession = di.get(),
                sessionAccess = di.get(),
                getGpgAgentApprovalWindow = getApprovalWindow,
                getGpgAgentApprovalCachePolicy = getApprovalCachePolicy,
                getGpgAgentFilter = getFilter,
                scope = scope,
                gpgPublicKeyRepository = di.get(),
                pendingUsageHistoryQueue = di.get(),
                sessionId = cryptoGenerator.seedHex(length = 16),
                json = di.get(),
                onApprovalRequest = requests::await,
            )
            scope.launch(Dispatchers.Main) {
                var previousSession: MasterSession.Key? = null
                di.get<GetVaultSession>()().collect { session ->
                    if (previousSession != null && previousSession !== session) requests.denyAll()
                    previousSession = session as? MasterSession.Key
                }
            }
            val authToken = cryptoGenerator.seed(32)
            handle = try {
                runtime.start(
                    scope,
                    GpgAgentRuntimeConfig(
                        authToken = authToken,
                        processor = processor,
                        onTerminated = { diagnostic ->
                            ctx.scope.launch {
                                lifecycleMutex.withLock {
                                    if (generation == thisGeneration) {
                                        stop()
                                        setStatus(GpgAgentRunState.FAILED, diagnostic ?: startFailureText())
                                    }
                                }
                            }
                        },
                        log = { logger.post("GpgAgent", it, LogLevel.INFO) },
                    ),
                )
            } finally {
                // The runtime owns a separate copy for its serving lifetime.
                authToken.fill(0)
            }
            if (handle?.isRunning == true) {
                setStatus(GpgAgentRunState.READY)
            } else {
                stop()
                setStatus(GpgAgentRunState.FAILED, startFailureText())
            }
        } catch (e: Throwable) {
            stop()
            e.throwIfFatalOrCancellation()
            setStatus(GpgAgentRunState.FAILED, e.message?.takeIf { it.isNotBlank() } ?: startFailureText())
        }
    }

    private suspend fun stop() = withContext(NonCancellable + Dispatchers.Main) {
        generation++
        val previousScope = runScope
        handle?.stop()
        handle = null
        previousScope?.cancel()
        runScope = null
        requests.denyAll()
        setStatus(GpgAgentRunState.STOPPED)
        // A restart must wait until the old helper releases its lifecycle lock
        // and socket. Cancellation alone would race the next startup.
        previousScope?.coroutineContext?.get(Job)?.cancelAndJoin()
        Unit
    }

    private fun setStatus(state: GpgAgentRunState, diagnostic: String? = null) {
        val socket = handle?.agentSocket ?: runtime.agentSocket
        status.value = GpgAgentStatusSnapshot(
            running = state == GpgAgentRunState.READY,
            enabled = false, // Projected from the persisted preference by the observer.
            state = state,
            gpgHome = handle?.gpgHome ?: runtime.gpgHome,
            agentSocket = socket,
            setupCommand = socket?.let(::macosSandboxGpgAgentSetupCommand),
            diagnostic = diagnostic,
        )
        ctx.koin.getOrNull<GpgAgentStatusService>()?.set(
            when (state) {
                GpgAgentRunState.UNSUPPORTED -> AgentStatus.Unsupported
                GpgAgentRunState.STOPPED -> AgentStatus.Stopped
                GpgAgentRunState.STARTING -> AgentStatus.Starting
                GpgAgentRunState.READY -> AgentStatus.Ready
                GpgAgentRunState.FAILED -> AgentStatus.Failed
            },
        )
    }

    private suspend fun startFailureText() =
        textResource(Res.string.error_failed_gpg_agent_start, ctx.koin.get<LeContext>())

    fun observeGpgAgentStatus(onChange: (GpgAgentStatusSnapshot) -> Unit): KeyguardCancellable =
        ctx.launchObserver {
            combine(getEnabled(), status) { enabled, value ->
                val socket = value.agentSocket ?: runtime.agentSocket
                value.copy(
                    enabled = enabled,
                    state = if (!runtime.isBinaryAvailable) GpgAgentRunState.UNSUPPORTED else value.state,
                    gpgHome = value.gpgHome ?: runtime.gpgHome,
                    agentSocket = socket,
                    setupCommand = socket?.let(::macosSandboxGpgAgentSetupCommand),
                )
            }.collectOnMain(onChange)
        }

    fun observeGpgAgentRequests(onChange: (List<GpgAgentRequestSnapshot>) -> Unit): KeyguardCancellable =
        ctx.launchObserver { requests.requests.collectOnMain(onChange) }

    fun resolveGpgAgentRequest(id: String, approved: Boolean) = requests.resolve(id, approved)

    fun observeGpgAgentSettings(onChange: (GpgAgentSettingsSnapshot) -> Unit): KeyguardCancellable =
        ctx.launchObserver {
            val context = ctx.koin.get<LeContext>()
            val approvals = combine(getApprovalWindow(), getApprovalWindowVariants(), getApprovalCachePolicy()) {
                    window, variants, policy -> Triple(window, variants, policy)
            }
            combine(
                getEnabled(),
                approvals,
                ctx.koin.get<GetGpgAgentDisplayKeyNames>()(),
                getFilter(),
            ) { enabled, approval, displayNames, filter ->
                val (window, variants, policy) = approval
                val snapshot = GpgAgentSettingsSnapshot(
                    loaded = true,
                    enabled = enabled,
                    approvalWindowTitle = approvalWindowTitle(window, context),
                    approvalWindowOptions = variants.map { duration ->
                        SettingOptionSnapshot(
                            id = duration.inWholeMilliseconds.toString(),
                            title = approvalWindowTitle(duration, context),
                            selected = duration == window,
                        )
                    },
                    approvalCachePolicyTitle = approvalCachePolicyTitle(policy, context),
                    approvalCachePolicyOptions = AgentApprovalCachePolicy.entries.map { candidate ->
                        SettingOptionSnapshot(
                            id = candidate.storageKey,
                            title = approvalCachePolicyTitle(candidate, context),
                            selected = candidate == policy,
                        )
                    },
                    displayKeyNames = displayNames,
                    filterActive = filter.normalize().isActive,
                )
                variants to snapshot
            }.collectOnMain { (variants, snapshot) ->
                latestApprovalWindowVariants = variants
                onChange(snapshot)
            }
        }

    fun setGpgAgentApprovalWindow(optionId: String) {
        val duration = latestApprovalWindowVariants
            .firstOrNull { it.inWholeMilliseconds.toString() == optionId }
            ?: return
        requests.denyAll()
        ctx.koin.get<PutGpgAgentApprovalWindow>()(duration).launchIn(ctx.scope)
    }

    fun setGpgAgentApprovalCachePolicy(optionId: String) {
        val policy = AgentApprovalCachePolicy.entries.firstOrNull { it.storageKey == optionId } ?: return
        requests.denyAll()
        ctx.koin.get<PutGpgAgentApprovalCachePolicy>()(policy).launchIn(ctx.scope)
    }

    fun setGpgAgentDisplayKeyNames(value: Boolean) {
        ctx.koin.get<PutGpgAgentDisplayKeyNames>()(value).launchIn(ctx.scope)
    }

    private suspend fun approvalWindowTitle(duration: Duration, context: LeContext): String = when (duration) {
        Duration.ZERO -> textResource(Res.string.pref_item_gpg_agent_approval_window_always_ask, context)
        Duration.INFINITE -> textResource(Res.string.pref_item_gpg_agent_approval_window_until_lock, context)
        else -> duration.format(context)
    }

    private suspend fun approvalCachePolicyTitle(policy: AgentApprovalCachePolicy, context: LeContext): String =
        textResource(
            when (policy) {
                AgentApprovalCachePolicy.Connection -> Res.string.pref_item_agent_approval_scope_connection
                AgentApprovalCachePolicy.Process -> Res.string.pref_item_agent_approval_scope_process
                AgentApprovalCachePolicy.Application -> Res.string.pref_item_agent_approval_scope_application
                AgentApprovalCachePolicy.ApplicationAndTerminalSession ->
                    Res.string.pref_item_agent_approval_scope_application_and_terminal_session
            },
            context,
        )

    fun observeGpgAgentFilters(
        onChange: (GpgAgentFiltersSnapshot) -> Unit,
        onClose: () -> Unit,
    ): KeyguardCancellable {
        val observationGeneration = ++filterObservationGeneration
        // The producer dispatches the pop intent from the background pipeline;
        // hop to the main scope before invoking the Swift-facing callback.
        val interceptor: (NavigationIntent) -> Boolean = { intent ->
            when (intent) {
                is NavigationIntent.Pop, is NavigationIntent.PopById -> {
                    ctx.scope.launch {
                        if (filterObservationGeneration == observationGeneration) onClose()
                    }
                    true
                }

                else -> false
            }
        }
        return ctx.launchObserver {
            try {
                ctx.unlockUseCase().collectLatest { state ->
                    if (state is VaultState.Main) {
                        coroutineScope {
                            val producerScope = this
                            val producerFlow = with(state.sessionKoin) {
                                ctx.koin.newHeadlessStateFlowScope("gpg_agent_filters", producerScope, interceptor)
                                    .gpgAgentFiltersStateProducer(
                                        filterContext = get(),
                                        getCipherFilters = get(),
                                        getGpgAgentFilter = get(),
                                        putGpgAgentFilter = get(),
                                        getCiphers = get(),
                                        getAccounts = get(),
                                        getProfiles = get(),
                                        getTags = get(),
                                        getFolders = get(),
                                        getCollections = get(),
                                        getOrganizations = get(),
                                    )
                            }
                            producerFlow
                                .map { loadable ->
                                    val filtersState = loadable.getOrNull()
                                    val handlers = LinkedHashMap<String, () -> Unit>()
                                    val snapshot = buildGpgAgentFiltersSnapshot(filtersState, handlers)
                                    Triple(filtersState, snapshot, handlers)
                                }
                                .collectOnMain { (filtersState, snapshot, handlers) ->
                                    if (filterObservationGeneration != observationGeneration) return@collectOnMain
                                    latestGpgAgentFiltersState = filtersState
                                    gpgAgentFilterHandlers = handlers
                                    onChange(snapshot)
                                }
                        }
                    } else {
                        ctx.publishOnMain {
                            if (filterObservationGeneration != observationGeneration) return@publishOnMain
                            latestGpgAgentFiltersState = null
                            gpgAgentFilterHandlers = emptyMap()
                            onChange(GpgAgentFiltersSnapshot.empty)
                        }
                    }
                }
            } finally {
                // Runs on teardown (cancellation included), so the main hop must
                // survive the cancelled job.
                withContext(NonCancellable + Dispatchers.Main) {
                    if (filterObservationGeneration != observationGeneration) return@withContext
                    latestGpgAgentFiltersState = null
                    gpgAgentFilterHandlers = emptyMap()
                }
            }
        }
    }

    private fun buildGpgAgentFiltersSnapshot(
        state: GpgAgentFiltersState?,
        handlers: LinkedHashMap<String, () -> Unit>,
    ): GpgAgentFiltersSnapshot {
        state ?: return GpgAgentFiltersSnapshot.empty
        val items = mapFilterItemsToSnapshots(state.filters, handlers)
        return GpgAgentFiltersSnapshot(
            loaded = true,
            count = state.count ?: 0,
            items = items,
            canSave = state.onSave != null,
            canReset = state.onReset != null,
        )
    }

    /** Toggles a filter on / off (or expands / collapses a section header) by id. */
    fun invokeGpgAgentFilter(id: String) {
        gpgAgentFilterHandlers[id]?.invoke()
    }

    /** Persists the pending filter; the producer pops itself on success. */
    fun saveGpgAgentFilters() {
        val onSave = latestGpgAgentFiltersState?.onSave ?: return
        requests.denyAll()
        onSave()
    }

    /** Clears the pending filter selection. No-op unless any filter is set. */
    fun resetGpgAgentFilters() {
        latestGpgAgentFiltersState?.onReset?.invoke()
    }

    fun observeGpgAgentHistory(onChange: (GpgAgentHistorySnapshot) -> Unit): KeyguardCancellable =
        ctx.launchSessionObserver(onLocked = { onChange(GpgAgentHistorySnapshot.empty) }) { state ->
            val producerScope = this
            with(state.sessionKoin) {
                ctx.koin.newHeadlessStateFlowScope("gpg_agent_history", producerScope)
                    .gpgAgentHistoryStateProducer(
                        cipherId = null,
                        getGpgUsageHistory = get(),
                        removeGpgUsageHistory = get(),
                        getCiphers = get(),
                        dateFormatter = get(),
                        confirmationRouteFactory = get(),
                        json = get(),
                    )
            }.map { it.getOrNull().toHistorySnapshot() }.collectOnMain(onChange)
        }

    /** The native surface presents confirmation before calling this mutation. */
    fun clearGpgAgentHistory() {
        ctx.backgroundScope.launch {
            val state = ctx.currentState() as? VaultState.Main ?: return@launch
            state.sessionKoin.get<RemoveGpgUsageHistory>()().launchIn(this)
        }
    }
}

internal fun GpgAgentHistoryState?.toHistorySnapshot(): GpgAgentHistorySnapshot {
    this ?: return GpgAgentHistorySnapshot.empty
    val items = items.map { item ->
        when (item) {
            is GpgAgentHistoryItem.Section -> GpgAgentHistoryItemSnapshot(
                id = item.id,
                kind = GpgAgentHistoryItemKind.SECTION,
                caller = item.text.orEmpty(),
                description = "",
                date = null,
                responseText = "",
                request = null,
                response = null,
            )
            is GpgAgentHistoryItem.Value -> GpgAgentHistoryItemSnapshot(
                id = item.id,
                kind = GpgAgentHistoryItemKind.VALUE,
                caller = item.caller,
                description = item.description,
                date = item.formattedDate,
                responseText = item.responseText,
                request = item.request.name,
                response = item.response.name,
            )
        }
    }
    return GpgAgentHistorySnapshot(true, subtitle, items, canClear = options.isNotEmpty())
}
