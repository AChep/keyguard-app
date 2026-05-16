package com.artemchep.keyguard.android.sshagent

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
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentTcpProtocol
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import com.artemchep.keyguard.common.service.sshagent.buildAndroidSshAgentCallerIdentity
import com.artemchep.keyguard.common.service.sshagent.launchSshAgentProxyBridge
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessorJvm
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.decodeOrNull
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.util.toHex
import java.util.LinkedHashSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance

class SshAgentService : Service(), DIAware {
    companion object {
        private const val TAG = "SshAgentService"
        private const val EXTRA_SENDER_APP_NAME = "com.artemchep.keyguard.extra.SSH_AGENT_SENDER_APP_NAME"
        private const val EXTRA_SENDER_APP_PACKAGE = "com.artemchep.keyguard.extra.SSH_AGENT_SENDER_APP_PACKAGE"

        fun getIntent(
            context: Context,
            protocolVersion: Int,
            proxyPort: Int,
            sessionId: String?,
            sessionSecret: String?,
            senderAppName: String? = null,
            senderAppPackageName: String? = null,
        ): Intent = Intent(context, SshAgentService::class.java).apply {
            action = SshAgentContract.ACTION_RUN_ANDROID_SSH_AGENT
            putExtra(SshAgentContract.EXTRA_PROTOCOL_VERSION, protocolVersion)
            putExtra(SshAgentContract.EXTRA_PROXY_PORT, proxyPort)
            putExtra(SshAgentContract.EXTRA_SESSION_ID, sessionId)
            putExtra(SshAgentContract.EXTRA_SESSION_SECRET, sessionSecret)
            putExtra(EXTRA_SENDER_APP_NAME, senderAppName)
            putExtra(EXTRA_SENDER_APP_PACKAGE, senderAppPackageName)
        }
    }

    private val scope: CoroutineScope = object : CoroutineScope {
        override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Main
    }

    override val di by closestDI { this }

    private val logRepository: LogRepository by instance()
    private val base64Service: Base64Service by instance()
    private val getVaultSession: GetVaultSession by instance()
    private val getSshAgentFilter: GetSshAgentFilter by instance()

    private val notificationIdPool = Notifications.sshAgent
    private var notificationId: Int? = null
    private var requestNotificationId: Int? = null
    private val requestNotificationTags = LinkedHashSet<String>()
    private val requestNotificationTagsLock = Any()

    private val bridgeTracker = SshAgentServiceBridgeTracker<Job>()

    private fun createRequestFlow(
        notificationTag: String,
    ) = AndroidSshAgentRequestFlow(
        enqueueRequest = SshRequestCoordinator::enqueue,
        onRequestQueued = { request, promptKind, requestUiShown ->
            cancelRequestNotification(notificationTag)
            if (!requestUiShown) {
                showRequestNotification(
                    notificationTag = notificationTag,
                    promptKind = promptKind,
                    caller = request.caller,
                )
            }
        },
        onRequestFinished = {
            cancelRequestNotification(notificationTag)
        },
        showRequestUi = {
            SshRequestCoordinator.show(applicationContext)
        },
    )

    private fun createRequestProcessor(
        requestFlow: AndroidSshAgentRequestFlow,
        sessionId: String,
        notificationTag: String,
    ): SshAgentRequestProcessor =
        SshAgentRequestProcessorJvm(
            logRepository = logRepository,
            getVaultSession = getVaultSession,
            getSshAgentFilter = getSshAgentFilter,
            scope = scope,
            sessionId = sessionId,
            onApprovalRequest = { caller, keyName, keyFingerprint ->
                requestSigningApproval(
                    requestFlow = requestFlow,
                    caller = caller,
                    keyName = keyName,
                    keyFingerprint = keyFingerprint,
                    notificationTag = notificationTag,
                )
            },
            onGetListRequest = { caller ->
                requestVaultUnlock(
                    requestFlow = requestFlow,
                    caller = caller,
                    notificationTag = notificationTag,
                )
            },
        )

    override fun onCreate() {
        super.onCreate()
        notificationId = notificationIdPool.obtainId()
        requestNotificationId = notificationIdPool.obtainId()
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

        if (intent?.action != SshAgentContract.ACTION_RUN_ANDROID_SSH_AGENT) {
            Log.w(TAG, "Ignoring unsupported action=${intent?.action}")
            bridgeTracker.requestStop(startId)?.let { stopStartId ->
                stopSelfResult(stopStartId)
            }
            return START_NOT_STICKY
        }

        val protocolVersion = intent.getIntExtra(
            SshAgentContract.EXTRA_PROTOCOL_VERSION,
            Int.MIN_VALUE,
        )
        val proxyPort = intent.getIntExtra(
            SshAgentContract.EXTRA_PROXY_PORT,
            -1,
        )
        val notificationTag = intent.getStringExtra(SshAgentContract.EXTRA_SESSION_ID)
            ?.takeUnless(String::isEmpty)
        val sessionId = notificationTag
            ?.let(base64Service::decodeOrNull)
        val sessionSecret = intent.getStringExtra(SshAgentContract.EXTRA_SESSION_SECRET)
            ?.let(base64Service::decodeOrNull)
        val senderAppInfo = buildAndroidSshAgentCallerIdentity(
            appName = intent.getStringExtra(EXTRA_SENDER_APP_NAME),
            appBundlePath = intent.getStringExtra(EXTRA_SENDER_APP_PACKAGE),
        )

        if (
            protocolVersion != SshAgentTcpProtocol.PROTOCOL_VERSION ||
            proxyPort !in 1..65535 ||
            notificationTag == null ||
            sessionId?.size != SshAgentTcpProtocol.SESSION_ID_LENGTH ||
            sessionSecret?.size != SshAgentTcpProtocol.SESSION_SECRET_LENGTH
        ) {
            Log.w(TAG, "Ignoring invalid SSH agent request")
            bridgeTracker.requestStop(startId)?.let { stopStartId ->
                stopSelfResult(stopStartId)
            }
            return START_NOT_STICKY
        }

        startForegroundWithType(
            notificationId = requireNotNull(notificationId),
            notification = SshAgentNotifications.createForegroundNotification(
                context = this,
                contentText = getString(R.string.notification_termux_ssh_content),
            ),
        )

        val requestFlow = createRequestFlow(notificationTag)
        val requestProcessor = createRequestProcessor(
            requestFlow = requestFlow,
            sessionId = sessionId.toHex(),
            notificationTag = notificationTag,
        )
        val bridgeFailureHandler = CoroutineExceptionHandler { _, error ->
            if (error is CancellationException) {
                return@CoroutineExceptionHandler
            }

            Log.w(TAG, "Failed to connect to SSH agent proxy port=$proxyPort", error)
            showBridgeErrorToast(error)
        }
        val bridgeJob = scope.launchSshAgentProxyBridge(
            requestProcessor = requestProcessor,
            proxyPort = proxyPort,
            sessionId = sessionId,
            sessionSecret = sessionSecret,
            senderAppInfo = senderAppInfo,
            context = Dispatchers.IO + bridgeFailureHandler,
            start = CoroutineStart.LAZY,
        )
        bridgeTracker.onBridgeStarted(
            startId = startId,
            bridge = bridgeJob,
        )
        bridgeJob.invokeOnCompletion {
            bridgeTracker.onBridgeFinished(bridgeJob)?.let { stopStartId ->
                stopSelfResult(stopStartId)
            }
        }
        bridgeJob.start()

        return START_NOT_STICKY
    }

    private fun showBridgeErrorToast(
        error: Throwable,
    ) {
        val baseMessage = getString(R.string.notification_termux_ssh_error)
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

    private suspend fun requestVaultUnlock(
        requestFlow: AndroidSshAgentRequestFlow,
        caller: SshAgentMessages.CallerIdentity?,
        notificationTag: String,
    ): Boolean {
        if (getVaultSession.valueOrNull is MasterSession.Key) {
            return true
        }
        logRepository.post(
            TAG,
            "Waiting for the user to unlock the vault for SSH request",
            LogLevel.INFO,
        )
        return requestFlow.requestVaultUnlock(
            caller = caller,
            notificationTag = notificationTag,
            timeout = SshAgentRequestProcessorJvm.APPROVAL_TIMEOUT_MS
                .toDuration(DurationUnit.MILLISECONDS),
        )
    }

    private suspend fun requestSigningApproval(
        requestFlow: AndroidSshAgentRequestFlow,
        caller: SshAgentMessages.CallerIdentity?,
        keyName: String,
        keyFingerprint: String,
        notificationTag: String,
    ): Boolean {
        logRepository.post(TAG, "Waiting for user approval for SSH signing", LogLevel.INFO)
        return requestFlow.requestSigningApproval(
            keyName = keyName,
            keyFingerprint = keyFingerprint,
            caller = caller,
            notificationTag = notificationTag,
            timeout = SshAgentRequestProcessorJvm.APPROVAL_TIMEOUT_MS
                .toDuration(DurationUnit.MILLISECONDS),
        )
    }

    private suspend fun showRequestNotification(
        notificationTag: String,
        promptKind: AndroidSshAgentPromptKind,
        caller: SshAgentMessages.CallerIdentity?,
    ) = withContext(Dispatchers.Main) {
        if (!canPostNotifications(this@SshAgentService)) {
            Log.w(
                TAG,
                "Unable to show SSH request notification because notifications are unavailable",
            )
            return@withContext
        }

        try {
            NotificationManagerCompat.from(this@SshAgentService).notify(
                notificationTag,
                requireNotNull(requestNotificationId),
                SshAgentNotifications.createRequestNotification(
                    context = this@SshAgentService,
                    notificationTag = notificationTag,
                    promptKind = promptKind,
                    appName = caller?.appName,
                ),
            )
            synchronized(requestNotificationTagsLock) {
                requestNotificationTags += notificationTag
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to post SSH request notification", e)
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
 * Tracks active SSH agent bridge sessions across overlapping service starts.
 *
 * Android may deliver multiple `onStartCommand()` calls before older bridge jobs finish.
 * The service must only stop after the last active bridge is gone, and it must use the
 * newest observed `startId` when calling `stopSelfResult(...)` so that an older request
 * cannot accidentally stop a newer service instance.
 */
public class SshAgentServiceBridgeTracker<T> {
    private val lock = Any()
    private val activeBridges = linkedSetOf<T>()

    // `stopSelfResult(startId)` only stops the service if `startId` is the most recent start.
    // Keep the highest seen id so any delayed bridge completion can stop the correct instance.
    private var latestStartId = 0

    fun onBridgeStarted(
        startId: Int,
        bridge: T,
    ) {
        synchronized(lock) {
            activeBridges += bridge
        }
        onStart(startId)
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
        val removed = activeBridges.remove(bridge)
        if (removed && activeBridges.isEmpty()) {
            // Once the last bridge finishes, stop the newest known start rather than the bridge's
            // original start id. This protects newer overlapping requests from being torn down.
            latestStartId
        } else {
            null
        }
    }

    fun drainActiveBridges(): List<T> = synchronized(lock) {
        activeBridges.toList().also {
            activeBridges.clear()
        }
    }

    fun requestStop(
        startId: Int,
    ): Int? = synchronized(lock) {
        if (activeBridges.isEmpty()) {
            // A stop may race with a newer `onStartCommand()`. Returning the max id keeps the
            // caller aligned with Android's "latest start wins" service-stop semantics.
            maxOf(latestStartId, startId)
        } else {
            null
        }
    }
}
