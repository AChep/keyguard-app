package com.artemchep.keyguard.apple.sshagent

import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.seedHex
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalPrompt
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessorImpl
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
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.sshagent.filter.SshAgentFiltersState
import com.artemchep.keyguard.feature.sshagent.filter.sshAgentFiltersStateProducer
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.apple.agent.AgentApprovalQueue
import com.artemchep.keyguard.apple.agent.snapshotCallerName
import com.artemchep.keyguard.apple.agent.snapshotCallerPath
import com.artemchep.keyguard.apple.core.AgentFilterActions
import com.artemchep.keyguard.apple.core.AgentFiltersSession
import com.artemchep.keyguard.apple.core.AgentFiltersSnapshot
import com.artemchep.keyguard.apple.core.agentFiltersFrame
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.completeOnPop
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.model.SettingOptionSnapshot
import com.artemchep.keyguard.feature.home.settings.component.agentApprovalWindowTitle
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.*
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.scope.Scope

/**
 * The on/off state is the shared persisted "ssh_agent" preference; [startSshAgentApplier] reacts to it.
 * Deliberately NOT started from [KeyguardCore] init — the AutoFill appex builds its own core and must
 * never spawn the agent.
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
    private val requests = AgentApprovalQueue(
        idPrefix = "ssh",
        idOf = SshAgentRequestSnapshot::id,
        toSnapshot = SshAgentApprovalPrompt::toSnapshot,
    )

    private var latestSshApprovalWindowVariants: List<Duration> = emptyList()

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

    fun setSshAgentEnabled(value: Boolean) {
        putSshAgent(value).launchIn(ctx.scope)
    }

    private suspend fun startSshAgent() {
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
        // previous run never flips the status of the current one.
        val generation = ++sshAgentGeneration
        val processor = SshAgentRequestProcessorImpl(
            logRepository = logRepository,
            getVaultSession = getVaultSession,
            sessionAccess = ctx.koin.get(),
            getSshAgentApprovalWindow = getSshAgentApprovalWindow,
            getSshAgentApprovalCachePolicy = ctx.koin.get(),
            getSshAgentFilter = getSshAgentFilter,
            scope = runScope,
            sshAgentPublicKeyRepository = sshAgentPublicKeyRepository,
            pendingUsageHistoryQueue = ctx.koin.get(),
            sessionId = cryptoGenerator.seedHex(length = 16),
            onApprovalRequest = requests::await,
        )
        val config = SshAgentRuntimeConfig(
            authToken = token,
            processor = processor,
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
        runScope.launch {
            requests.denyWhenSessionEnds(getVaultSession)
        }
        val handle = try {
            sshAgentRuntime.start(runScope, config)
        } finally {
            // The runtime owns a separate copy for its serving lifetime.
            token.fill(0)
        }
        if (handle != null) {
            sshAgentHandle = handle
            sshAgentStatusService.set(AgentStatus.Ready)
        } else {
            runScope.cancel()
            sshAgentRunScope = null
            sshAgentStatusService.set(AgentStatus.Failed)
        }
    }

    private fun stopSshAgent() {
        // Bump the generation so a termination callback from the stopped run is
        // ignored — an intentional stop must not be reported as a crash.
        sshAgentGeneration++
        sshAgentHandle?.stop()
        sshAgentHandle = null
        sshAgentRunScope?.cancel()
        sshAgentRunScope = null
        requests.denyAll()
        sshAgentStatusService.set(AgentStatus.Stopped)
    }

    fun observeSshAgentRequests(
        onChange: (List<SshAgentRequestSnapshot>) -> Unit,
    ): KeyguardCancellable = ctx.launchObserver { requests.requests.collectOnMain(onChange) }

    fun resolveSshAgentRequest(id: String, approved: Boolean) = requests.resolve(id, approved)

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
                    approvalWindowTitle = agentApprovalWindowTitle(approvalWindow.first, leContext),
                    approvalWindowOptions = approvalWindow.second.map { duration ->
                        SettingOptionSnapshot(
                            id = duration.inWholeMilliseconds.toString(),
                            title = agentApprovalWindowTitle(duration, leContext),
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

    fun setSshAgentApprovalWindow(optionId: String) {
        val duration = latestSshApprovalWindowVariants
            .firstOrNull { it.inWholeMilliseconds.toString() == optionId }
            ?: return
        putSshAgentApprovalWindow(duration).launchIn(ctx.scope)
    }

    fun setSshAgentDisplayKeyNames(value: Boolean) {
        putSshAgentDisplayKeyNames(value).launchIn(ctx.scope)
    }

    fun makeSshAgentFiltersSession(): AgentFiltersSession = AgentFiltersSession { publish, complete ->
        ctx.launchSessionObserver(
            onLocked = { publish(AgentFiltersSnapshot.empty, AgentFilterActions()) },
            onTeardown = { publish(AgentFiltersSnapshot.empty, AgentFilterActions()) },
        ) { state ->
            val producerScope = this
            val interceptor = producerScope.completeOnPop(ctx.scope, complete)
            val producerFlow = sshAgentFiltersStateFlow(producerScope, state.sessionKoin, interceptor)
            producerFlow
                .map { loadable ->
                    val state = loadable.getOrNull()
                    agentFiltersFrame(state?.filters, state?.count, state?.onSave, state?.onReset)
                }
                .collectOnMain { (snapshot, actions) -> publish(snapshot, actions) }
        }
    }

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

}

internal fun SshAgentApprovalPrompt.toSnapshot(
    id: String,
    timeoutMs: Long,
    expiresAtEpochMs: Long,
) = SshAgentRequestSnapshot(
    id = id,
    keyName = keyName,
    keyFingerprint = keyFingerprint,
    callerName = caller.snapshotCallerName(),
    callerPath = caller.snapshotCallerPath(),
    timeoutMs = timeoutMs,
    expiresAtEpochMs = expiresAtEpochMs,
)
