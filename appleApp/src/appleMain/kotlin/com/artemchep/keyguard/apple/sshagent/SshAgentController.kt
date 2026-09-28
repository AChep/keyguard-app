package com.artemchep.keyguard.apple.sshagent

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.main
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.seedHex
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentStatusService
import com.artemchep.keyguard.common.usecase.GetSshAgent
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindowVariants
import com.artemchep.keyguard.common.usecase.GetSshAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetSshAgentStatus
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.PutSshAgent
import com.artemchep.keyguard.common.usecase.PutSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.PutSshAgentDisplayKeyNames
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.sshagent.filter.SshAgentFiltersState
import com.artemchep.keyguard.feature.sshagent.filter.sshAgentFiltersStateProducer
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.model.SettingOptionSnapshot
import com.artemchep.keyguard.apple.model.mapFilterItemsToSnapshots
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.format
import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.scope.Scope

/** The per-sign approval window, in ms. Mirrors the IPC processor's own timeout. */
private const val SSH_APPROVAL_TIMEOUT_MS = 60_000L

/**
 * The SSH agent: the run lifecycle (spawns the reused binary over a POSIX IPC
 * socket, gates each signature through a per-request approval surfaced to Swift),
 * the Developer-pane settings, and the per-key filters screen. The on/off state
 * is the shared persisted "ssh_agent" preference; [startSshAgentApplier] reacts
 * to it. Deliberately NOT started from [KeyguardCore] init — the AutoFill appex
 * builds its own core and must never spawn the agent.
 */
internal class SshAgentController(
    private val ctx: CoreContext,
) {
    private val getSshAgent: GetSshAgent by lazy { ctx.koin.get() }
    private val putSshAgent: PutSshAgent by lazy { ctx.koin.get() }
    private val getSshAgentApprovalWindow: GetSshAgentApprovalWindow by lazy { ctx.koin.get() }
    private val getSshAgentApprovalWindowVariants: GetSshAgentApprovalWindowVariants by lazy {
        ctx.koin.get()
    }
    private val putSshAgentApprovalWindow: PutSshAgentApprovalWindow by lazy { ctx.koin.get() }
    private val getSshAgentDisplayKeyNames: GetSshAgentDisplayKeyNames by lazy { ctx.koin.get() }
    private val putSshAgentDisplayKeyNames: PutSshAgentDisplayKeyNames by lazy { ctx.koin.get() }
    private val getSshAgentFilter: GetSshAgentFilter by lazy { ctx.koin.get() }
    private val getSshAgentStatus: GetSshAgentStatus by lazy { ctx.koin.get() }
    private val sshAgentStatusService: SshAgentStatusService by lazy { ctx.koin.get() }
    private val sshAgentPublicKeyRepository: SshAgentPublicKeyRepository by lazy { ctx.koin.get() }
    private val getVaultSession: GetVaultSession by lazy { ctx.koin.get() }

    private val sshAgentRuntime: SshAgentRuntime = createSshAgentRuntime()
    private var sshAgentHandle: SshAgentRuntimeHandle? = null
    private var sshAgentGeneration = 0
    private var sshAgentRunScope: CoroutineScope? = null
    private var sshAgentApplierJob: Job? = null
    private val sshApprovals = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private var sshPendingRequests: List<SshAgentRequestSnapshot> = emptyList()
    private var sshRequestsHandler: ((List<SshAgentRequestSnapshot>) -> Unit)? = null
    private var sshRequestCounter = 0

    private var latestSshApprovalWindowVariants: List<Duration> = emptyList()
    private var latestSshAgentFiltersState: SshAgentFiltersState? = null
    private var sshAgentFilterHandlers: Map<String, () -> Unit> = emptyMap()

    fun startSshAgentApplier() {
        if (sshAgentApplierJob != null) return
        sshAgentApplierJob = ctx.scope.launch {
            getSshAgent()
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        startSshAgent()
                    } else {
                        stopSshAgent()
                    }
                }
        }
    }

    /** Persists the shared "SSH agent" preference; the applier reacts to it. */
    fun setSshAgentEnabled(value: Boolean) {
        putSshAgent(value).launchIn(ctx.scope)
    }

    /** Starts the SSH agent (idempotent). Requires the bundled binary + a signer. */
    private fun startSshAgent() {
        if (sshAgentHandle?.isRunning == true) return
        if (!sshAgentRuntime.isBinaryAvailable) {
            sshAgentStatusService.set(AgentStatus.Unsupported)
            return
        }
        sshAgentStatusService.set(AgentStatus.Starting)

        val runScope = CoroutineScope(
            ctx.scope.coroutineContext + Dispatchers.Default + SupervisorJob(ctx.scope.coroutineContext[Job]),
        )
        sshAgentRunScope = runScope

        val cryptoGenerator = ctx.koin.get<CryptoGenerator>()
        val logRepository = ctx.koin.get<LogRepository>()
        val token = cryptoGenerator.seed(32)
        // Each run gets a fresh generation so a stale termination callback from a
        // previous run never flips the status of the current one (the previous
        // identity check on the concrete manager moved behind the runtime seam).
        val generation = ++sshAgentGeneration
        val config = SshAgentRuntimeConfig(
            authToken = token,
            sessionId = cryptoGenerator.seedHex(length = 16),
            logRepository = logRepository,
            getVaultSession = getVaultSession,
            getSshAgentApprovalWindow = getSshAgentApprovalWindow,
            getSshAgentApprovalCachePolicy = ctx.koin.get(),
            getSshAgentFilter = getSshAgentFilter,
            sshAgentPublicKeyRepository = sshAgentPublicKeyRepository,
            onApproval = { info -> awaitSshApproval(info) },
            onTerminated = {
                // Arbitrary thread; hop to the main scope before touching state.
                ctx.scope.launch {
                    if (sshAgentGeneration == generation) {
                        sshAgentStatusService.set(AgentStatus.Failed)
                    }
                }
            },
            log = { message -> logRepository.post("SshAgent", message, LogLevel.INFO) },
        )
        val handle = sshAgentRuntime.start(runScope, config)
        if (handle != null) {
            sshAgentHandle = handle
            sshAgentStatusService.set(AgentStatus.Ready)
        } else {
            runScope.cancel()
            sshAgentRunScope = null
            sshAgentStatusService.set(AgentStatus.Failed)
        }
    }

    /** Stops the SSH agent and its IPC server. */
    private fun stopSshAgent() {
        // Bump the generation so a termination callback from the stopped run is
        // ignored — an intentional stop must not be reported as a crash.
        sshAgentGeneration++
        sshAgentHandle?.stop()
        sshAgentHandle = null
        sshAgentRunScope?.cancel()
        sshAgentRunScope = null
        // Fail any in-flight approvals.
        sshApprovals.values.forEach { it.complete(false) }
        sshApprovals.clear()
        sshPendingRequests = emptyList()
        notifySshRequests()
        sshAgentStatusService.set(AgentStatus.Stopped)
    }

    /** Observes pending per-sign approval requests for the approval window. */
    fun observeSshAgentRequests(
        onChange: (List<SshAgentRequestSnapshot>) -> Unit,
    ): KeyguardCancellable {
        sshRequestsHandler = onChange
        onChange(sshPendingRequests)
        val job = ctx.scope.launch {
            try {
                awaitCancellation()
            } finally {
                sshRequestsHandler = null
            }
        }
        return KeyguardCancellable(job)
    }

    /** Resolves a pending approval request: approve (true) or deny (false). */
    fun resolveSshAgentRequest(id: String, approved: Boolean) {
        sshApprovals[id]?.complete(approved)
    }

    fun observeSshAgentStatus(
        onChange: (SshAgentStatusSnapshot) -> Unit,
    ): KeyguardCancellable {
        val job = ctx.scope.launch {
            combine(
                getSshAgent(),
                getSshAgentStatus(),
            ) { enabled, status ->
                SshAgentStatusSnapshot(
                    running = status == AgentStatus.Ready,
                    enabled = enabled,
                    state = when (status) {
                        AgentStatus.Unsupported -> SshAgentRunState.UNSUPPORTED
                        AgentStatus.Stopped -> SshAgentRunState.STOPPED
                        AgentStatus.Starting -> SshAgentRunState.STARTING
                        AgentStatus.Ready -> SshAgentRunState.READY
                        AgentStatus.Failed -> SshAgentRunState.FAILED
                    },
                    sshAuthSock = sshAgentHandle?.sshAuthSockPath ?: sshAgentRuntime.sshAuthSockPath,
                )
            }.collect { onChange(it) }
        }
        return KeyguardCancellable(job)
    }

    private suspend fun awaitSshApproval(info: SshAgentApprovalInfo): Boolean {
        val id = "ssh-${++sshRequestCounter}"
        val deferred = CompletableDeferred<Boolean>()
        val timeoutMs = SSH_APPROVAL_TIMEOUT_MS
        val expiresAtEpochMs = Clock.System.now().toEpochMilliseconds() + timeoutMs
        withContext(Dispatchers.Main) {
            sshApprovals[id] = deferred
            sshPendingRequests = sshPendingRequests + SshAgentRequestSnapshot(
                id = id,
                keyName = info.keyName,
                keyFingerprint = info.keyFingerprint,
                callerName = info.callerName,
                callerPath = info.callerPath,
                timeoutMs = timeoutMs,
                expiresAtEpochMs = expiresAtEpochMs,
            )
            notifySshRequests()
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            // Always drain the request — even when the IPC processor's own approval
            // timeout (or a dropped connection / agent stop) cancels this coroutine —
            // so the approval panel can never get stuck on screen.
            withContext(NonCancellable + Dispatchers.Main) {
                sshApprovals.remove(id)
                sshPendingRequests = sshPendingRequests.filterNot { it.id == id }
                notifySshRequests()
            }
        }
    }

    private fun notifySshRequests() {
        sshRequestsHandler?.invoke(sshPendingRequests)
    }

    fun observeSshAgentSettings(
        onChange: (SshAgentSettingsSnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        val approvalWindowFlow = combine(
            getSshAgentApprovalWindow(),
            getSshAgentApprovalWindowVariants(),
        ) { current, variants -> current to variants }
        val job = ctx.scope.launch {
            combine(
                getSshAgent(),
                approvalWindowFlow,
                getSshAgentDisplayKeyNames(),
                getSshAgentFilter(),
            ) { enabled, approvalWindow, displayKeyNames, filter ->
                latestSshApprovalWindowVariants = approvalWindow.second
                SshAgentSettingsSnapshot(
                    loaded = true,
                    enabled = enabled,
                    approvalWindowTitle = sshApprovalWindowTitle(approvalWindow.first, leContext),
                    approvalWindowOptions = approvalWindow.second.map { duration ->
                        SettingOptionSnapshot(
                            id = duration.inWholeMilliseconds.toString(),
                            title = sshApprovalWindowTitle(duration, leContext),
                            selected = duration == approvalWindow.first,
                        )
                    },
                    displayKeyNames = displayKeyNames,
                    filterActive = filter.normalize().isActive,
                )
            }.collect { onChange(it) }
        }
        return KeyguardCancellable(job)
    }

    private suspend fun sshApprovalWindowTitle(duration: Duration, context: LeContext): String =
        when (duration) {
            Duration.ZERO -> textResource(Res.string.pref_item_ssh_agent_approval_window_always_ask, context)
            Duration.INFINITE -> textResource(Res.string.pref_item_ssh_agent_approval_window_until_lock, context)
            else -> duration.format(context)
        }

    fun setSshAgentApprovalWindow(optionId: String) {
        val duration = latestSshApprovalWindowVariants
            .firstOrNull { it.inWholeMilliseconds.toString() == optionId }
            ?: return
        putSshAgentApprovalWindow(duration).launchIn(ctx.scope)
    }

    fun setSshAgentDisplayKeyNames(value: Boolean) {
        putSshAgentDisplayKeyNames(value).launchIn(ctx.scope)
    }

    fun observeSshAgentFilters(
        onChange: (SshAgentFiltersSnapshot) -> Unit,
        onClose: () -> Unit,
    ): KeyguardCancellable {
        // The producer dispatches the pop intent from the background pipeline;
        // hop to the main scope before invoking the Swift-facing callback.
        val interceptor: (NavigationIntent) -> Boolean = { intent ->
            when (intent) {
                is NavigationIntent.Pop, is NavigationIntent.PopById -> {
                    ctx.scope.launch { onClose() }
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
                            val producerFlow = sshAgentFiltersStateFlow(producerScope, state.sessionKoin, interceptor)
                            producerFlow
                                .map { loadable ->
                                    val filtersState = loadable.getOrNull()
                                    val handlers = LinkedHashMap<String, () -> Unit>()
                                    val snapshot = buildSshAgentFiltersSnapshot(filtersState, handlers)
                                    Triple(filtersState, snapshot, handlers)
                                }
                                .collectOnMain { (filtersState, snapshot, handlers) ->
                                    latestSshAgentFiltersState = filtersState
                                    sshAgentFilterHandlers = handlers
                                    onChange(snapshot)
                                }
                        }
                    } else {
                        ctx.publishOnMain {
                            latestSshAgentFiltersState = null
                            sshAgentFilterHandlers = emptyMap()
                            onChange(SshAgentFiltersSnapshot.empty)
                        }
                    }
                }
            } finally {
                // Runs on teardown (cancellation included), so the main hop must
                // survive the cancelled job.
                withContext(NonCancellable + Dispatchers.Main) {
                    latestSshAgentFiltersState = null
                    sshAgentFilterHandlers = emptyMap()
                }
            }
        }
    }

    /** Runs the shared [sshAgentFiltersStateProducer] in a headless scope tied to [scope]. */
    private suspend fun sshAgentFiltersStateFlow(
        scope: CoroutineScope,
        sessionKoin: Scope,
        interceptor: (NavigationIntent) -> Boolean,
    ): Flow<Loadable<SshAgentFiltersState>> = with(sessionKoin) {
        ctx.koin.newHeadlessStateFlowScope("ssh_agent_filters", scope, interceptor)
            .sshAgentFiltersStateProducer(
                filterContext = get(),
                getCipherFilters = get(),
                getSshAgentFilter = get(),
                putSshAgentFilter = get(),
                getCiphers = get(),
                getAccounts = get(),
                getProfiles = get(),
                getTags = get(),
                getFolders = get(),
                getCollections = get(),
                getOrganizations = get(),
            )
    }

    private fun buildSshAgentFiltersSnapshot(
        state: SshAgentFiltersState?,
        handlers: LinkedHashMap<String, () -> Unit>,
    ): SshAgentFiltersSnapshot {
        state ?: return SshAgentFiltersSnapshot.empty
        val items = mapFilterItemsToSnapshots(state.filters, handlers)
        return SshAgentFiltersSnapshot(
            loaded = true,
            count = state.count ?: 0,
            items = items,
            canSave = state.onSave != null,
            canReset = state.onReset != null,
        )
    }

    /** Toggles a filter on / off (or expands / collapses a section header) by id. */
    fun invokeSshAgentFilter(id: String) {
        sshAgentFilterHandlers[id]?.invoke()
    }

    /** Persists the pending filter; the producer pops itself on success. */
    fun saveSshAgentFilters() {
        latestSshAgentFiltersState?.onSave?.invoke()
    }

    /** Clears the pending filter selection. No-op unless any filter is set. */
    fun resetSshAgentFilters() {
        latestSshAgentFiltersState?.onReset?.invoke()
    }
}
