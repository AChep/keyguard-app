package com.artemchep.keyguard.android.agentbridge

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.artemchep.keyguard.android.Notifications
import com.artemchep.keyguard.android.util.canPostNotifications
import com.artemchep.keyguard.common.service.agent.AgentApprovalBroker
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.agent.denyWhenSessionEnds
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentApprovalPrompt
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentApprovalWindowMemory
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestProcessorImpl
import com.artemchep.keyguard.common.service.gpgagent.runGpgAgentProxyBridge
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalPrompt
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalWindowMemory
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessorImpl
import com.artemchep.keyguard.common.service.sshagent.SshAgentTcpProtocol
import com.artemchep.keyguard.common.service.sshagent.androidBridgeAuthorization
import com.artemchep.keyguard.common.service.sshagent.buildAndroidSshAgentCallerIdentity
import com.artemchep.keyguard.common.service.sshagent.runSshAgentProxyBridge
import com.artemchep.keyguard.common.service.sshagent.toApprovalRequest
import com.artemchep.keyguard.common.service.gpgagent.toApprovalRequest as toGpgApprovalRequest
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.decodeOrNull
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.crypto.NativeGpgAgentCrypto
import com.artemchep.keyguard.di.KeyguardKoinOwner
import com.artemchep.keyguard.di.keyguardKoin
import java.util.LinkedHashSet
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects the app back to the Termux helpers of every [AgentBridge], one
 * bridge job per admitted session, and asks the user to approve their requests.
 */
class AgentBridgeService : Service(), KeyguardKoinOwner {
    companion object {
        private const val TAG = "AgentBridgeService"
        private const val EXTRA_SENDER_APP_NAME = "com.artemchep.keyguard.extra.AGENT_BRIDGE_SENDER_APP_NAME"
        private const val EXTRA_SENDER_APP_PACKAGE = "com.artemchep.keyguard.extra.AGENT_BRIDGE_SENDER_APP_PACKAGE"
        private const val EXTRA_SENDER_APP_PRINCIPAL =
            "com.artemchep.keyguard.extra.AGENT_BRIDGE_SENDER_APP_PRINCIPAL"

        fun getIntent(
            context: Context,
            bridge: AgentBridge,
            protocolVersion: Int,
            proxyPort: Int,
            sessionId: String?,
            sessionSecret: String?,
            senderAppName: String? = null,
            senderAppPackageName: String? = null,
            senderAppPrincipalFingerprint: ByteArray? = null,
        ): Intent = Intent(context, AgentBridgeService::class.java).apply {
            action = bridge.action
            putExtra(bridge.extraProtocolVersion, protocolVersion)
            putExtra(bridge.extraProxyPort, proxyPort)
            putExtra(bridge.extraSessionId, sessionId)
            putExtra(bridge.extraSessionSecret, sessionSecret)
            putExtra(EXTRA_SENDER_APP_NAME, senderAppName)
            putExtra(EXTRA_SENDER_APP_PACKAGE, senderAppPackageName)
            putExtra(EXTRA_SENDER_APP_PRINCIPAL, senderAppPrincipalFingerprint)
        }
    }

    private val scope: CoroutineScope = object : CoroutineScope {
        override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Main
    }

    override val koin get() = this.keyguardKoin()

    private val logRepository: LogRepository by lazy { koin.get() }
    private val base64Service: Base64Service by lazy { koin.get() }
    private val getVaultSession: GetVaultSession by lazy { koin.get() }
    private val getSshAgentApprovalWindow: GetSshAgentApprovalWindow by lazy { koin.get() }
    private val getSshAgentApprovalCachePolicy: GetSshAgentApprovalCachePolicy by lazy { koin.get() }
    private val getSshAgentFilter: GetSshAgentFilter by lazy { koin.get() }
    private val sshAgentPublicKeyRepository: SshAgentPublicKeyRepository by lazy { koin.get() }
    private val pendingUsageHistoryQueue: PendingUsageHistoryQueue by lazy { koin.get() }
    private val approvalWindowMemory: SshAgentApprovalWindowMemory by lazy { koin.get() }
    private val gpgApprovalWindowMemory: GpgAgentApprovalWindowMemory by lazy { koin.get() }
    private val getGpgAgentApprovalWindow: GetGpgAgentApprovalWindow by lazy { koin.get() }
    private val getGpgAgentApprovalCachePolicy: GetGpgAgentApprovalCachePolicy by lazy { koin.get() }
    private val getGpgAgentFilter: GetGpgAgentFilter by lazy { koin.get() }
    private val gpgPublicKeyRepository: GpgPublicKeyRepository by lazy { koin.get() }

    private val notificationIdPool = Notifications.sshAgent
    private var notificationId: Int? = null
    private var requestNotificationId: Int? = null
    private val requestNotificationTags = LinkedHashSet<String>()
    private val requestNotificationTagsLock = Any()

    private val bridgeTracker = AgentBridgeServiceBridgeTracker<Job>()

    private fun createSshRequestProcessor(
        sessionId: String,
        notificationTag: String,
    ): SshAgentRequestProcessor =
        SshAgentRequestProcessorImpl(
            sessionAccess = koin.get(),
            logRepository = logRepository,
            getVaultSession = getVaultSession,
            getSshAgentApprovalWindow = getSshAgentApprovalWindow,
            getSshAgentApprovalCachePolicy = getSshAgentApprovalCachePolicy,
            getSshAgentFilter = getSshAgentFilter,
            scope = scope,
            approvalWindowMemory = approvalWindowMemory,
            sshAgentPublicKeyRepository = sshAgentPublicKeyRepository,
            pendingUsageHistoryQueue = pendingUsageHistoryQueue,
            sessionId = sessionId,
            onApprovalRequest = { prompt ->
                requestSshApproval(
                    prompt = prompt,
                    notificationTag = notificationTag,
                )
            },
        )

    private fun createGpgRequestProcessor(
        sessionId: String,
        notificationTag: String,
    ): GpgAgentRequestProcessor =
        GpgAgentRequestProcessorImpl(
            logRepository = logRepository,
            crypto = NativeGpgAgentCrypto,
            getVaultSession = getVaultSession,
            sessionAccess = koin.get(),
            getGpgAgentApprovalWindow = getGpgAgentApprovalWindow,
            getGpgAgentApprovalCachePolicy = getGpgAgentApprovalCachePolicy,
            getGpgAgentFilter = getGpgAgentFilter,
            scope = scope,
            approvalWindowMemory = gpgApprovalWindowMemory,
            gpgPublicKeyRepository = gpgPublicKeyRepository,
            pendingUsageHistoryQueue = pendingUsageHistoryQueue,
            sessionId = sessionId,
            onApprovalRequest = { prompt ->
                requestGpgApproval(
                    prompt = prompt,
                    notificationTag = notificationTag,
                )
            },
        )

    override fun onCreate() {
        super.onCreate()
        notificationId = notificationIdPool.obtainId()
        requestNotificationId = notificationIdPool.obtainId()
        scope.launch { AgentRequestCoordinator.sshApprovals.denyWhenSessionEnds(getVaultSession()) }
        scope.launch { AgentRequestCoordinator.gpgApprovals.denyWhenSessionEnds(getVaultSession()) }
    }

    override fun onDestroy() {
        bridgeTracker.drainActiveBridges().forEach { bridgeJob ->
            bridgeJob.cancel()
        }
        cancelAllRequestNotifications()
        notificationId?.let(notificationIdPool::releaseId)
        requestNotificationId?.let(notificationIdPool::releaseId)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?,
    ): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        bridgeTracker.onStart(startId)

        val bridge = AgentBridge.ofAction(intent?.action)
        if (intent == null || bridge == null) {
            Log.w(TAG, "Ignoring unsupported action=${intent?.action}")
            stopOrUpdateNotification(bridgeTracker.requestStop(startId))
            return START_NOT_STICKY
        }

        val protocolVersion = intent.getIntExtra(
            bridge.extraProtocolVersion,
            Int.MIN_VALUE,
        )
        val proxyPort = intent.getIntExtra(
            bridge.extraProxyPort,
            -1,
        )
        val notificationTag = intent.getStringExtra(bridge.extraSessionId)
            ?.takeUnless(String::isEmpty)
        val sessionId = notificationTag
            ?.let(base64Service::decodeOrNull)
        val sessionSecret = intent.getStringExtra(bridge.extraSessionSecret)
            ?.let(base64Service::decodeOrNull)
        val senderAppInfo = buildAndroidSshAgentCallerIdentity(
            appName = intent.getStringExtra(EXTRA_SENDER_APP_NAME),
            appBundlePath = intent.getStringExtra(EXTRA_SENDER_APP_PACKAGE),
            authorization = androidBridgeAuthorization(
                intent.getByteArrayExtra(EXTRA_SENDER_APP_PRINCIPAL),
            ),
        )

        if (
            protocolVersion != SshAgentTcpProtocol.PROTOCOL_VERSION ||
            proxyPort !in 1..65535 ||
            notificationTag == null ||
            sessionId?.size != SshAgentTcpProtocol.SESSION_ID_LENGTH ||
            sessionSecret?.size != SshAgentTcpProtocol.SESSION_SECRET_LENGTH
        ) {
            Log.w(TAG, "Ignoring invalid $bridge agent bridge request")
            notificationTag?.let { sessionIdValue ->
                bridge.admission.reject(
                    sessionId = sessionIdValue,
                    outcome = AgentBridgeContract.BroadcastOutcome.INVALID,
                )
            }
            stopOrUpdateNotification(bridgeTracker.requestStop(startId))
            return START_NOT_STICKY
        }

        try {
            // Android requires a foreground start for every service start,
            // so this also brings the notification up to date.
            startForegroundWithType(
                notificationId = requireNotNull(notificationId),
                notification = AgentBridgeNotifications.createForegroundNotification(
                    context = this,
                    bridges = bridgeTracker.activeAgents() + bridge,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to promote $bridge agent bridge service", e)
            bridge.admission.reject(
                sessionId = notificationTag,
                outcome = AgentBridgeContract.BroadcastOutcome.START_FAILED,
            )
            stopOrUpdateNotification(bridgeTracker.requestStop(startId))
            return START_NOT_STICKY
        }

        if (!bridge.admission.canStart(notificationTag)) {
            Log.w(TAG, "Rejecting $bridge agent bridge: admission reservation is unavailable")
            val exception = kotlin.run {
                val msg = "Agent bridge admission is unavailable."
                IllegalStateException(msg)
            }
            showBridgeErrorToast(bridge, exception)
            stopOrUpdateNotification(bridgeTracker.requestStop(startId))
            return START_NOT_STICKY
        }

        if (!bridgeTracker.tryReserve(startId)) {
            Log.w(TAG, "Rejecting duplicate $bridge agent bridge service start")
            bridge.admission.reject(
                sessionId = notificationTag,
                outcome = AgentBridgeContract.BroadcastOutcome.START_FAILED,
            )
            stopOrUpdateNotification(bridgeTracker.requestStop(startId))
            return START_NOT_STICKY
        }

        val bridgeJob = try {
            val bridgeFailureHandler = CoroutineExceptionHandler { _, error ->
                if (error is CancellationException) {
                    return@CoroutineExceptionHandler
                }

                Log.w(TAG, "$bridge agent bridge failed, proxy port=$proxyPort", error)
                showBridgeErrorToast(bridge, error)
            }
            val onOversizeResponse: (Int) -> Unit = { size ->
                Log.w(
                    TAG,
                    "Answered a $bridge agent request with an error: its $size-byte response " +
                        "exceeds the bridge limit of ${SshAgentTcpProtocol.MAX_FRAME_PAYLOAD_SIZE} bytes",
                )
            }
            val enabled = bridge.isEnabled(koin)
            when (bridge) {
                AgentBridge.SSH -> {
                    val requestProcessor = createSshRequestProcessor(
                        sessionId = sessionId.toHex(),
                        notificationTag = notificationTag,
                    )
                    scope.launch(Dispatchers.IO + bridgeFailureHandler, CoroutineStart.LAZY) {
                        runWhileAgentEnabled(enabled) {
                            runSshAgentProxyBridge(
                                requestProcessor = requestProcessor,
                                proxyPort = proxyPort,
                                sessionId = sessionId,
                                sessionSecret = sessionSecret,
                                senderAppInfo = senderAppInfo,
                                onOversizeResponse = onOversizeResponse,
                            )
                        }
                    }
                }

                AgentBridge.GPG -> {
                    val requestProcessor = createGpgRequestProcessor(
                        sessionId = sessionId.toHex(),
                        notificationTag = notificationTag,
                    )
                    scope.launch(Dispatchers.IO + bridgeFailureHandler, CoroutineStart.LAZY) {
                        runWhileAgentEnabled(enabled) {
                            runGpgAgentProxyBridge(
                                requestProcessor = requestProcessor,
                                proxyPort = proxyPort,
                                sessionId = sessionId,
                                sessionSecret = sessionSecret,
                                senderAppInfo = senderAppInfo,
                                onOversizeResponse = onOversizeResponse,
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create $bridge agent bridge", e)
            bridge.admission.reject(
                sessionId = notificationTag,
                outcome = AgentBridgeContract.BroadcastOutcome.START_FAILED,
            )
            stopOrUpdateNotification(bridgeTracker.onReservationCancelled(startId))
            return START_NOT_STICKY
        }

        val registered = bridgeTracker.onBridgeStarted(
            startId = startId,
            bridge = bridgeJob,
            agent = bridge,
        )
        if (!registered) {
            bridgeJob.cancel()
            Log.w(TAG, "Rejecting $bridge agent bridge: reservation was lost")
            bridge.admission.reject(
                sessionId = notificationTag,
                outcome = AgentBridgeContract.BroadcastOutcome.START_FAILED,
            )
            stopOrUpdateNotification(bridgeTracker.requestStop(startId))
            return START_NOT_STICKY
        }
        bridgeJob.invokeOnCompletion {
            bridge.admission.release(notificationTag)
            stopOrUpdateNotification(bridgeTracker.onBridgeFinished(bridgeJob))
        }
        if (!bridge.admission.markActive(notificationTag)) {
            Log.w(TAG, "Rejecting $bridge agent bridge: admission reservation was lost")
            bridgeJob.cancel()
            return START_NOT_STICKY
        }
        if (!bridgeJob.start()) {
            Log.w(TAG, "Failed to start the admitted $bridge agent bridge")
            bridgeJob.cancel()
        }

        return START_NOT_STICKY
    }

    private fun showBridgeErrorToast(
        bridge: AgentBridge,
        error: Throwable,
    ) {
        val baseMessage = getString(bridge.text.error)
        val detail = error.localizedMessage
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeUnless { it.contains('\n') }
        val message = if (detail != null) {
            "$baseMessage: $detail"
        } else {
            baseMessage
        }
        scope.launch(Dispatchers.Main.immediate) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Stops the service once no bridge is left, which also removes its
     * notification. Otherwise updates the notification to the agents that remain.
     */
    private fun stopOrUpdateNotification(
        stopStartId: Int?,
    ) {
        if (stopStartId != null) {
            stopSelfResult(stopStartId)
        } else {
            updateForegroundNotification()
        }
    }

    /**
     * Shows the agents with active bridges. Bridges finish on background
     * threads, so the update reads them on the main thread, after any service
     * start in progress, and an older update never replaces a newer one.
     */
    private fun updateForegroundNotification() {
        scope.launch {
            val bridges = bridgeTracker.activeAgents()
            // The service is stopping, and its notification goes with it.
            if (bridges.isEmpty()) {
                return@launch
            }
            if (!canPostNotifications(this@AgentBridgeService)) {
                return@launch
            }

            try {
                NotificationManagerCompat.from(this@AgentBridgeService).notify(
                    requireNotNull(notificationId),
                    AgentBridgeNotifications.createForegroundNotification(
                        context = this@AgentBridgeService,
                        bridges = bridges,
                    ),
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to update the agent bridge notification", e)
            }
        }
    }

    private fun startForegroundWithType(
        notificationId: Int,
        notification: Notification,
    ) {
        ServiceCompat.startForeground(
            this,
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private suspend fun requestSshApproval(
        prompt: SshAgentApprovalPrompt,
        notificationTag: String,
    ): Boolean {
        logRepository.post(TAG, "Waiting for user approval for SSH signing", LogLevel.INFO)
        return requestApproval(
            bridge = AgentBridge.SSH,
            notificationTag = notificationTag,
            caller = prompt.caller,
        ) {
            AgentRequestCoordinator.sshApprovals.request { expiresAt ->
                prompt.toApprovalRequest(
                    id = UUID.randomUUID().toString(),
                    expiresAt = expiresAt,
                    notificationTag = notificationTag,
                )
            }
        }
    }

    private suspend fun requestGpgApproval(
        prompt: GpgAgentApprovalPrompt,
        notificationTag: String,
    ): Boolean {
        logRepository.post(TAG, "Waiting for user approval for a GPG operation", LogLevel.INFO)
        return requestApproval(
            bridge = AgentBridge.GPG,
            notificationTag = notificationTag,
            caller = prompt.caller,
        ) {
            AgentRequestCoordinator.gpgApprovals.request(deadline = prompt.expiresAt) { expiresAt ->
                prompt.toGpgApprovalRequest(
                    id = UUID.randomUUID().toString(),
                    expiresAt = expiresAt,
                    notificationTag = notificationTag,
                )
            }
        }
    }

    /** Queues a request with [enqueue], shows its prompt, and falls back to a notification. */
    private suspend fun requestApproval(
        bridge: AgentBridge,
        notificationTag: String,
        caller: CallerIdentity?,
        enqueue: suspend () -> AgentApprovalBroker.Result,
    ): Boolean {
        return coroutineScope {
            var notification: Job? = null
            try {
                // Starting undispatched queues the request before its UI is shown.
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    enqueue()
                }
                val requestUiShown = AgentRequestCoordinator.show(applicationContext)
                cancelRequestNotification(notificationTag)
                // The notification reopens the prompt. It also appears once
                // the prompt closes while the request waits, for example
                // after the user switched apps or swiped it away.
                notification = launch(start = CoroutineStart.UNDISPATCHED) {
                    if (requestUiShown) {
                        AgentRequestCoordinator.prompts.awaitClosed()
                    }
                    showRequestNotification(
                        bridge = bridge,
                        notificationTag = notificationTag,
                        caller = caller,
                    )
                }
                result.await() == AgentApprovalBroker.Result.Approved
            } finally {
                // A notification being posted on the main thread
                // must be posted before it can be cancelled.
                withContext(NonCancellable) {
                    notification?.cancelAndJoin()
                }
                cancelRequestNotification(notificationTag)
            }
        }
    }

    private suspend fun showRequestNotification(
        bridge: AgentBridge,
        notificationTag: String,
        caller: CallerIdentity?,
    ) = withContext(Dispatchers.Main) {
        if (!canPostNotifications(this@AgentBridgeService)) {
            Log.w(
                TAG,
                "Unable to show $bridge request notification because notifications are unavailable",
            )
            return@withContext
        }

        try {
            NotificationManagerCompat.from(this@AgentBridgeService).notify(
                notificationTag,
                requireNotNull(requestNotificationId),
                AgentBridgeNotifications.createRequestNotification(
                    context = this@AgentBridgeService,
                    bridge = bridge,
                    notificationTag = notificationTag,
                    appName = caller?.appName,
                ),
            )
            synchronized(requestNotificationTagsLock) {
                requestNotificationTags += notificationTag
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to post $bridge request notification", e)
        }
    }

    private fun cancelRequestNotification(
        notificationTag: String,
    ) {
        val id = requestNotificationId ?: return
        synchronized(requestNotificationTagsLock) {
            requestNotificationTags -= notificationTag
        }
        NotificationManagerCompat.from(this).cancel(notificationTag, id)
    }

    private fun cancelAllRequestNotifications() {
        val id = requestNotificationId ?: return
        val tags = synchronized(requestNotificationTagsLock) {
            requestNotificationTags.toList().also {
                requestNotificationTags.clear()
            }
        }
        val notificationManager = NotificationManagerCompat.from(this)
        tags.forEach { notificationTag ->
            notificationManager.cancel(notificationTag, id)
        }
    }
}

/**
 * Runs a [bridge] until it ends or its agent is [enabled] no longer, as
 * desktop and Apple stop a disabled agent. Its pending prompts end with it,
 * and a deferred start that arrives after the agent was disabled never runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun runWhileAgentEnabled(
    enabled: Flow<Boolean>,
    bridge: suspend () -> Unit,
) {
    enabled
        .mapLatest { if (it) bridge() }
        .first()
}

/**
 * Tracks active agent bridge sessions across overlapping service starts.
 *
 * Android may deliver multiple `onStartCommand()` calls before older bridge jobs finish.
 * The service must only stop after the last active bridge is gone, and it must use the
 * newest observed `startId` when calling `stopSelfResult(...)` so that an older request
 * cannot accidentally stop a newer service instance.
 */
public class AgentBridgeServiceBridgeTracker<T> {
    private val lock = Any()
    private val activeBridges = linkedMapOf<T, AgentBridge>()
    private val pendingStarts = linkedSetOf<Int>()

    // `stopSelfResult(startId)` only stops the service if `startId` is the most recent start.
    // Keep the highest seen id so any delayed bridge completion can stop the correct instance.
    private var latestStartId = 0

    /**
     * Tracks a pending Android service start before its bridge job is created.
     * Bridge resource admission is owned by [AgentBridgeAdmission].
     */
    fun tryReserve(
        startId: Int,
    ): Boolean = synchronized(lock) {
        latestStartId = maxOf(latestStartId, startId)
        if (startId in pendingStarts) {
            false
        } else {
            pendingStarts += startId
            true
        }
    }

    fun onBridgeStarted(
        startId: Int,
        bridge: T,
        agent: AgentBridge,
    ): Boolean = synchronized(lock) {
        latestStartId = maxOf(latestStartId, startId)
        if (startId !in pendingStarts || bridge in activeBridges) {
            false
        } else {
            pendingStarts -= startId
            activeBridges[bridge] = agent
            true
        }
    }

    /** The agents with at least one active bridge. */
    fun activeAgents(): Set<AgentBridge> = synchronized(lock) {
        activeBridges.values.toSet()
    }

    fun onStart(
        startId: Int,
    ) {
        synchronized(lock) {
            // Record every delivered start, even if it never creates a bridge.
            // Validation failures still advance the `startId` that must be used
            // later when deciding whether the service can stop.
            latestStartId = maxOf(latestStartId, startId)
        }
    }

    fun onBridgeFinished(
        bridge: T,
    ): Int? = synchronized(lock) {
        val removed = activeBridges.remove(bridge) != null
        if (removed && activeBridges.isEmpty() && pendingStarts.isEmpty()) {
            // Once the last bridge finishes, stop the newest known start rather than the bridge's
            // original start id. This protects newer overlapping requests from being torn down.
            latestStartId
        } else {
            null
        }
    }

    fun onReservationCancelled(
        startId: Int,
    ): Int? = synchronized(lock) {
        val removed = pendingStarts.remove(startId)
        if (removed && pendingStarts.isEmpty() && activeBridges.isEmpty()) {
            latestStartId
        } else {
            null
        }
    }

    fun drainActiveBridges(): List<T> = synchronized(lock) {
        activeBridges.keys.toList().also {
            activeBridges.clear()
            pendingStarts.clear()
        }
    }

    fun requestStop(
        startId: Int,
    ): Int? = synchronized(lock) {
        if (activeBridges.isEmpty() && pendingStarts.isEmpty()) {
            // A stop may race with a newer `onStartCommand()`. Returning the max id keeps the
            // caller aligned with Android's "latest start wins" service-stop semantics.
            maxOf(latestStartId, startId)
        } else {
            null
        }
    }
}
