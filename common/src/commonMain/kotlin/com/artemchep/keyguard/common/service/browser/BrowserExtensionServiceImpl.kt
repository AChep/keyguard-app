package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.agent.AGENT_APPROVAL_PROMPT_TIMEOUT
import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheConfigProvider
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.AgentDuplex
import com.artemchep.keyguard.common.service.agent.AgentDuplexChannel
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.agent.fixedAgentApprovalCacheConfig
import com.artemchep.keyguard.common.service.cli.agent.CliAgentAnswer
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.CliAgentUsageRecorder
import com.artemchep.keyguard.common.service.cli.agent.cliAgentError
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.session.BrowserExtensionSessionAccess
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.usecase.GetBrowserExtensionEnabled
import com.artemchep.keyguard.common.usecase.GetBrowserExtensionRemovedConnectors
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.PutBrowserExtensionEnabled
import com.artemchep.keyguard.common.usecase.PutBrowserExtensionRemovedConnectors
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserOrigin
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserProtocol
import com.artemchep.keyguard.feature.generator.PasswordGeneratorConfigReader
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serves the browser extensions that the CLI agent relays: each accepted
 * [stream][open] becomes a [BrowserSession]. App-scoped, so that extensions
 * learn the vault's state and ask to unlock it while it is locked. Inert until
 * the platform runtime reports the integration as
 * [supported][configureRuntime] and the user turns it on.
 */
@Suppress("LongParameterList", "TooManyFunctions") // One dependency per concern of the sessions.
class BrowserExtensionServiceImpl(
    getVaultSession: GetVaultSession,
    sessionAccess: BrowserExtensionSessionAccess,
    getBrowserExtensionEnabled: GetBrowserExtensionEnabled,
    private val putBrowserExtensionEnabled: PutBrowserExtensionEnabled,
    private val getBrowserExtensionRemovedConnectors: GetBrowserExtensionRemovedConnectors,
    private val putBrowserExtensionRemovedConnectors: PutBrowserExtensionRemovedConnectors,
    private val cryptoGenerator: CryptoGenerator,
    store: BrowserPairingStore,
    private val tldService: TldService,
    private val passwordGenerator: PasswordGeneratorConfigReader,
    /** What the passkey requests need beyond the vault. */
    private val passkeys: BrowserPasskeys,
    private val logRepository: LogRepository,
    scope: CoroutineScope,
    /** Writes the request log; `null` keeps none. */
    recorder: CliAgentUsageRecorder? = null,
    /** How long a paired browser keeps an approval to fill an item on a site. */
    approvalCacheConfig: AgentApprovalCacheConfigProvider<AgentApprovalCachePolicy> =
        fixedAgentApprovalCacheConfig(1.hours, AgentApprovalCachePolicy.Default),
    /** How long a prompt waits for the user. */
    approvalTimeout: suspend () -> Duration = { AGENT_APPROVAL_PROMPT_TIMEOUT },
    /** How long the handshake of a session may take. */
    private val handshakeTimeoutMs: Long = BrowserProtocol.HANDSHAKE_TIMEOUT_MS,
    timeSource: TimeSource = TimeSource.Monotonic,
) : BrowserExtensionHost, BrowserExtensionStreams {
    private val mutableState = MutableStateFlow(BrowserExtensionState())
    override val state = mutableState.asStateFlow()

    private val vaultAccess = BrowserVaultAccess(
        getVaultSession = getVaultSession,
        sessionAccess = sessionAccess,
    )

    private val browserApprovals = BrowserApprovals(
        getVaultSession = getVaultSession,
        vaultAccess = vaultAccess,
        cryptoGenerator = cryptoGenerator,
        approvalCacheConfig = approvalCacheConfig,
        approvalTimeout = approvalTimeout,
        scope = scope,
        timeSource = timeSource,
    )
    override val approvals = browserApprovals.prompts

    private val pairingAttempts = BrowserPairingAttempts(timeSource)

    private val saveWindows = BrowserSaveWindows(approvalTimeout)
    override val saves = saveWindows.saves

    private val requestLog = BrowserRequestLog(recorder, scope, logRepository)

    private val pairings = BrowserPairings(
        store = store,
        vaultAccess = vaultAccess,
        cryptoGenerator = cryptoGenerator,
    )

    /** Whether the integration serves extensions; a session ends once it does not. */
    private val active: Flow<Boolean> = state
        .map { it.needsAgent }
        .distinctUntilChanged()

    /** Serializes the changes of the removed connectors, which read the set and write it back. */
    private val removedConnectorsMutex = Mutex()

    init {
        scope.launch {
            // Together, so that the integration never turns on before the
            // removed connectors load: it would install them again.
            combine(
                getBrowserExtensionEnabled(),
                getBrowserExtensionRemovedConnectors(),
                ::Pair,
            ).collect { (enabled, removedConnectors) ->
                val previous = mutableState.value.enabled
                mutableState.update {
                    it.copy(
                        enabled = enabled,
                        removedConnectors = removedConnectors,
                    )
                }
                if (previous != enabled) browserApprovals.invalidate()
            }
        }
        scope.launch {
            pairings.listed.collect { list ->
                mutableState.update { it.copy(pairings = list) }
            }
        }
    }

    override fun configureRuntime(supported: Boolean) {
        mutableState.update { it.copy(supported = supported) }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        putBrowserExtensionEnabled(enabled).bind()
        mutableState.update { it.copy(enabled = enabled) }
        browserApprovals.invalidate()
    }

    override fun respondToApproval(id: String, approved: Boolean): Boolean =
        browserApprovals.respond(id, approved)

    override fun namePairing(id: String, name: String): Boolean =
        browserApprovals.namePairing(id, name)

    override fun setAddUri(id: String, add: Boolean): Boolean =
        browserApprovals.setAddUri(id, add)

    override suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean): Boolean =
        browserApprovals.confirmIdentity(id, verify)

    override fun selectPasskey(id: String, choice: Int): Boolean =
        browserApprovals.selectPasskey(id, choice)

    override fun useAnotherDevice(id: String): Boolean =
        browserApprovals.useAnotherDevice(id)

    override fun onSaveShown(id: String) = saveWindows.onShown(id)

    override fun closeSave(id: String) = saveWindows.close(id)

    override suspend fun removePairing(id: String) {
        pairings.remove(id)
    }

    override suspend fun renamePairing(id: String, name: String) {
        // Bounded like the name the user gives while pairing.
        val bounded = name.boundedName()
            ?: return
        pairings.rename(id, bounded)
    }

    override suspend fun setConnectorsRemoved(browsers: Set<BrowserConnectorBrowser>, removed: Boolean) {
        removedConnectorsMutex.withLock {
            // The stored set, which the state may not have loaded yet.
            val current = getBrowserExtensionRemovedConnectors().first()
            val removedConnectors = if (removed) current + browsers else current - browsers
            putBrowserExtensionRemovedConnectors(removedConnectors).bind()
            mutableState.update { it.copy(removedConnectors = removedConnectors) }
        }
    }

    /**
     * Opens the stream of an extension. Refuses it while the integration is
     * off, for a connector of another revision, or past
     * [BrowserProtocol.MAX_SESSIONS] sessions. An accepted stream holds its
     * slot of the cap until it closes.
     */
    override suspend fun open(request: CliAgentMessages.BrowserOpenRequest): CliAgentAnswer {
        val session = BrowserExtensionSession(
            id = cryptoGenerator.uuid(),
            origin = request.origin,
            caller = request.caller?.toBrowserCaller() ?: BrowserCaller(),
            verifiedHost = request.verifiedHost,
        )
        val refusal = when {
            !state.value.needsAgent -> "integration_disabled"
            request.hostRevision != HOST_REVISION -> "update_required"
            // The agent validated it as well.
            !BrowserOrigin.isValid(request.origin) -> "invalid_request"
            !reserve(session) -> "too_many_sessions"
            else -> null
        }
        if (refusal != null) {
            return CliAgentAnswer(cliAgentError(refusal))
        }
        return CliAgentAnswer.opened(SessionDuplex(session))
    }

    /** Shows the session [id] as paired by [pairingId]. */
    private fun onPaired(id: String, pairingId: String) {
        mutableState.update { state ->
            state.copy(
                sessions = state.sessions.map { if (it.id == id) it.copy(pairingId = pairingId) else it },
            )
        }
    }

    /** Takes a slot of the session cap for [session]; `false` when none is left. */
    private fun reserve(session: BrowserExtensionSession): Boolean {
        var reserved = false
        mutableState.update { state ->
            reserved = state.sessions.size < BrowserProtocol.MAX_SESSIONS
            if (reserved) state.copy(sessions = state.sessions + session) else state
        }
        return reserved
    }

    private fun release(id: String) {
        mutableState.update { state -> state.copy(sessions = state.sessions.filterNot { it.id == id }) }
    }

    private fun newSession(session: BrowserExtensionSession) = BrowserSession(
        initial = session,
        pairings = pairings,
        vaultAccess = vaultAccess,
        approvals = browserApprovals,
        pairingAttempts = pairingAttempts,
        requestLog = requestLog,
        saveWindows = saveWindows,
        cryptoGenerator = cryptoGenerator,
        tldService = tldService,
        passwordGenerator = passwordGenerator,
        passkeys = passkeys,
        logRepository = logRepository,
        active = active,
        onPaired = ::onPaired,
        handshakeTimeoutMs = handshakeTimeoutMs,
    )

    /** The stream of one session, which holds its slot until it closes. */
    private inner class SessionDuplex(
        private val session: BrowserExtensionSession,
    ) : AgentDuplex {
        override val maxPacketSize: Int get() = BrowserProtocol.MAX_FRAME_SIZE

        override suspend fun serve(channel: AgentDuplexChannel) {
            newSession(session).run(BrowserDuplexStream(channel))
        }

        override fun close() = release(session.id)
    }

    private companion object {
        /** The revision of `keyguard-browser-host` that this app speaks to. */
        const val HOST_REVISION = 1
    }
}

/** The caller attached by the agent, for display. The prompt bounds every value. */
private fun CallerIdentity.toBrowserCaller() = BrowserCaller(
    pid = pid,
    processName = processName,
    executablePath = executablePath,
    appName = appName,
    appBundlePath = appBundlePath,
)
