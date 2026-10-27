package com.artemchep.keyguard.android.agentbridge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.artemchep.keyguard.android.Notifications
import com.artemchep.keyguard.android.util.canPostNotifications
import com.artemchep.keyguard.android.util.getAndroidPackageSigningCertificates
import com.artemchep.keyguard.common.service.agent.AgentCallerAuthorizationSchema
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.sshagent.SshAgentTcpProtocol
import com.artemchep.keyguard.common.service.sshagent.buildAndroidFrameworkPackageAuthorization
import com.artemchep.keyguard.common.service.sshagent.buildAndroidSshAgentCallerIdentity
import com.artemchep.keyguard.common.usecase.GetGpgAgent
import com.artemchep.keyguard.common.usecase.GetSshAgent
import com.artemchep.keyguard.di.keyguardKoin
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin

/**
 * Admits a Termux helper's bridge session and starts [AgentBridgeService]
 * to connect back to it.
 */
abstract class AgentBridgeReceiver(
    private val bridge: AgentBridge,
) : BroadcastReceiver() {
    companion object {
        private const val TAG = "AgentBridgeReceiver"
        private const val BUSY_LOG_INTERVAL_MS = 10_000L
        private const val SERVICE_START_ACK_TIMEOUT_MS = 5_000L

        private var lastBusyLogAtMs: Long? = null

        @Synchronized
        private fun logBusyRejection() {
            val nowMs = SystemClock.elapsedRealtime()
            val previousMs = lastBusyLogAtMs
            if (previousMs == null || nowMs < previousMs || nowMs - previousMs >= BUSY_LOG_INTERVAL_MS) {
                lastBusyLogAtMs = nowMs
                Log.w(TAG, "Rejecting agent bridge broadcast: bridge admission limit reached")
            }
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != bridge.action) {
            return
        }

        // Broadcast sender attribution is only guaranteed while onReceive is
        // active. Capture it before goAsync/return, then resolve the package's
        // framework metadata from these immutable values on the IO coroutine.
        val sender = captureSender()
        val request = try {
            parseRequest(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse $bridge agent bridge broadcast", e)
            setBroadcastOutcome(AgentBridgeContract.BroadcastOutcome.INVALID)
            return
        }
        if (request == null) {
            setBroadcastOutcome(AgentBridgeContract.BroadcastOutcome.INVALID)
            return
        }

        val reservation = when (
            val reserveResult = bridge.admission.tryReserve(request.sessionId)
        ) {
            is AgentBridgeAdmission.ReserveResult.Reserved -> reserveResult.reservation
            AgentBridgeAdmission.ReserveResult.Busy,
            AgentBridgeAdmission.ReserveResult.Duplicate,
            -> {
                logBusyRejection()
                setBroadcastOutcome(AgentBridgeContract.BroadcastOutcome.BUSY)
                return
            }
        }
        val ordered = isOrderedBroadcast
        val result = try {
            goAsync()
        } catch (e: Exception) {
            bridge.admission.reject(
                sessionId = reservation.sessionId,
                outcome = AgentBridgeContract.BroadcastOutcome.START_FAILED,
            )
            Log.w(TAG, "Failed to begin asynchronous $bridge agent bridge broadcast handling", e)
            setBroadcastOutcome(AgentBridgeContract.BroadcastOutcome.START_FAILED)
            return
        }

        try {
            val koin = context.keyguardKoin()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                var outcome = AgentBridgeContract.BroadcastOutcome.INTERNAL_ERROR
                try {
                    // Verify that the agent is enabled
                    // in the settings.
                    val enabled = bridge.isEnabled(koin).first()
                    if (!enabled) {
                        if (!canPostNotifications(context)) {
                            val msg =
                                "Unable to show $bridge agent rejection notification because notifications are unavailable"
                            Log.w(TAG, msg)
                        } else try {
                            NotificationManagerCompat.from(context).notify(
                                Notifications.sshAgent.obtainId(),
                                AgentBridgeNotifications.createServiceRejectedNotification(context, bridge),
                            )
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Unable to post $bridge agent rejection notification", e)
                        }
                        outcome = AgentBridgeContract.BroadcastOutcome.DISABLED
                    } else {
                        outcome = handleRequest(
                            context = context,
                            request = request,
                            reservation = reservation,
                            sender = sender,
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to handle $bridge agent bridge broadcast", e)
                } finally {
                    try {
                        if (!outcome.accepted) {
                            bridge.admission.reject(
                                sessionId = reservation.sessionId,
                                outcome = outcome,
                            )
                        }
                        result.setBroadcastOutcome(
                            bridge = bridge,
                            outcome = outcome,
                            ordered = ordered,
                        )
                    } finally {
                        result.finish()
                    }
                }
            }
        } catch (e: Exception) {
            try {
                bridge.admission.reject(
                    sessionId = reservation.sessionId,
                    outcome = AgentBridgeContract.BroadcastOutcome.INTERNAL_ERROR,
                )
                result.setBroadcastOutcome(
                    bridge = bridge,
                    outcome = AgentBridgeContract.BroadcastOutcome.INTERNAL_ERROR,
                    ordered = ordered,
                )
            } finally {
                result.finish()
            }
            Log.w(TAG, "Failed to start $bridge agent bridge broadcast handling", e)
        }
    }

    private suspend fun handleRequest(
        context: Context,
        request: BroadcastRequest,
        reservation: AgentBridgeAdmission.Reservation,
        sender: BroadcastSender?,
    ): AgentBridgeContract.BroadcastOutcome {
        val senderAppInfo = getSentFromAppInfo(context, sender)
        val senderAppName = senderAppInfo?.appName
        val serviceIntent = AgentBridgeService.getIntent(
            context = context,
            bridge = bridge,
            protocolVersion = request.protocolVersion,
            proxyPort = request.proxyPort,
            sessionId = request.sessionId,
            sessionSecret = request.sessionSecret,
            senderAppName = senderAppName,
            senderAppPackageName = senderAppInfo?.appBundlePath,
            senderAppPrincipalFingerprint =
                senderAppInfo?.authorization
                    ?.subjects
                    ?.singleOrNull { subject ->
                        subject.kind ==
                            AgentCallerAuthorizationSchema.SubjectKind.STABLE_APPLICATION &&
                            subject.evidenceSource ==
                            AgentCallerAuthorizationSchema.EvidenceSource
                                .ANDROID_FRAMEWORK_PACKAGE
                    }
                    ?.fingerprint,
        )
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "$bridge agent bridge service start rejected, showing a request notification", e)
            if (!canPostNotifications(context)) {
                val msg =
                    "Unable to show $bridge agent recovery notification because notifications are unavailable"
                Log.w(TAG, msg)
                return AgentBridgeContract.BroadcastOutcome.START_FAILED
            }

            return try {
                val notification = AgentBridgeNotifications.createServiceStartupNotification(
                    context = context,
                    bridge = bridge,
                    serviceIntent = serviceIntent,
                    appName = senderAppName,
                )
                NotificationManagerCompat.from(context).notify(
                    Notifications.sshAgent.obtainId(),
                    notification,
                )
                if (bridge.admission.markDeferred(reservation.sessionId)) {
                    AgentBridgeContract.BroadcastOutcome.DEFERRED
                } else {
                    AgentBridgeContract.BroadcastOutcome.START_FAILED
                }
            } catch (notificationException: SecurityException) {
                Log.w(TAG, "Unable to post $bridge agent recovery notification", notificationException)
                AgentBridgeContract.BroadcastOutcome.START_FAILED
            }
        } catch (e: Throwable) {
            Log.w(TAG, "$bridge agent bridge service start rejected", e)
            return AgentBridgeContract.BroadcastOutcome.START_FAILED
        }

        return try {
            withTimeout(SERVICE_START_ACK_TIMEOUT_MS) {
                reservation.outcome.await()
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "Timed out waiting for the $bridge agent bridge service to start")
            AgentBridgeContract.BroadcastOutcome.START_FAILED
        }
    }

    private fun setBroadcastOutcome(
        outcome: AgentBridgeContract.BroadcastOutcome,
    ) {
        if (!isOrderedBroadcast) {
            return
        }
        setResult(
            outcome.resultCode(),
            outcome.wireValue(bridge),
            null,
        )
    }

    private fun parseRequest(
        intent: Intent,
    ): BroadcastRequest? {
        val protocolVersion = intent.getIntExtra(
            bridge.extraProtocolVersion,
            Int.MIN_VALUE,
        )
        if (protocolVersion != SshAgentTcpProtocol.PROTOCOL_VERSION) {
            Log.w(TAG, "Ignoring unsupported $bridge agent bridge protocol version=$protocolVersion")
            return null
        }

        val proxyPort = intent.getIntExtra(
            bridge.extraProxyPort,
            -1,
        )
        if (proxyPort !in 1..65535) {
            Log.w(TAG, "Ignoring invalid $bridge agent bridge proxy port=$proxyPort")
            return null
        }

        val sessionId = intent.getStringExtra(bridge.extraSessionId)
        val sessionSecret = intent.getStringExtra(bridge.extraSessionSecret)
        if (
            !isValidAgentBridgeSessionParameter(
                value = sessionId,
                expectedDecodedSize = SshAgentTcpProtocol.SESSION_ID_LENGTH,
            ) ||
            !isValidAgentBridgeSessionParameter(
                value = sessionSecret,
                expectedDecodedSize = SshAgentTcpProtocol.SESSION_SECRET_LENGTH,
            )
        ) {
            Log.w(TAG, "Ignoring invalid $bridge agent bridge session parameters")
            return null
        }

        return BroadcastRequest(
            protocolVersion = protocolVersion,
            proxyPort = proxyPort,
            sessionId = requireNotNull(sessionId),
            sessionSecret = requireNotNull(sessionSecret),
        )
    }

    private fun getSentFromAppInfo(
        context: Context,
        sender: BroadcastSender?,
    ): CallerIdentity? {
        val packageName = sender
            ?.packageName
            ?: return null
        val senderUid = sender.uid
        val packagesForUid = context.packageManager.getPackagesForUid(senderUid).orEmpty()
        if (packageName !in packagesForUid) {
            val msg = "Broadcast sender package=$packageName is not owned by uid=$senderUid"
            Log.w(TAG, msg)
            return null
        }

        val signingCertificates = getSigningCertificates(context, packageName)
            ?: return null
        val authorization = buildAndroidFrameworkPackageAuthorization(
            packageName = packageName,
            signingCertificates = signingCertificates,
        ) ?: return null

        return buildAndroidSshAgentCallerIdentity(
            appName = resolveAppLabel(context, packageName),
            appBundlePath = packageName,
            authorization = authorization,
        )
    }

    private fun captureSender(): BroadcastSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return null
        }
        val packageName = sentFromPackage
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return BroadcastSender(
            packageName = packageName,
            uid = sentFromUid,
        )
    }

    private fun getSigningCertificates(
        context: Context,
        packageName: String,
    ): List<ByteArray>? = context.packageManager
        .getAndroidPackageSigningCertificates(packageName)
        ?.currentOrHistory
        ?.takeIf(List<ByteArray>::isNotEmpty)

    @Suppress("DEPRECATION")
    private fun resolveAppLabel(
        context: Context,
        packageName: String,
    ): String? = runCatching {
        val packageManager = context.packageManager
        val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                android.content.pm.PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            packageManager.getApplicationInfo(packageName, 0)
        }
        packageManager.getApplicationLabel(applicationInfo)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private data class BroadcastSender(
        val packageName: String,
        val uid: Int,
    )

    private data class BroadcastRequest(
        val protocolVersion: Int,
        val proxyPort: Int,
        val sessionId: String,
        val sessionSecret: String,
    )
}

private const val MAX_ENCODED_SESSION_PARAMETER_LENGTH = 128

internal fun isValidAgentBridgeSessionParameter(
    value: String?,
    expectedDecodedSize: Int,
): Boolean {
    if (
        value == null ||
        value.isEmpty() ||
        value.length > MAX_ENCODED_SESSION_PARAMETER_LENGTH ||
        expectedDecodedSize <= 0
    ) {
        return false
    }
    return runCatching {
        Base64.getDecoder().decode(value).size == expectedDecodedSize
    }.getOrDefault(false)
}

/** Whether the user enabled the agent of this bridge in the settings. */
internal fun AgentBridge.isEnabled(koin: Koin): Flow<Boolean> = when (this) {
    AgentBridge.SSH -> koin.get<GetSshAgent>()()
    AgentBridge.GPG -> koin.get<GetGpgAgent>()()
}

private fun AgentBridgeContract.BroadcastOutcome.resultCode(): Int = if (accepted) {
    Activity.RESULT_OK
} else {
    Activity.RESULT_CANCELED
}

private fun BroadcastReceiver.PendingResult.setBroadcastOutcome(
    bridge: AgentBridge,
    outcome: AgentBridgeContract.BroadcastOutcome,
    ordered: Boolean,
) {
    if (!ordered) {
        return
    }
    setResult(
        outcome.resultCode(),
        outcome.wireValue(bridge),
        null,
    )
}
