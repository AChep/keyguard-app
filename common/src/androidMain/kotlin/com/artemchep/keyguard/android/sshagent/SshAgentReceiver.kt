package com.artemchep.keyguard.android.sshagent

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
import com.artemchep.keyguard.common.service.sshagent.SshAgentTcpProtocol
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import com.artemchep.keyguard.common.service.sshagent.buildAndroidFrameworkPackageAuthorization
import com.artemchep.keyguard.common.service.sshagent.buildAndroidSshAgentCallerIdentity
import com.artemchep.keyguard.common.usecase.GetSshAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.kodein.di.android.closestDI
import org.kodein.di.instance
import java.util.Base64
import kotlin.getValue

class SshAgentReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SshAgentReceiver"
        private const val BUSY_LOG_INTERVAL_MS = 10_000L
        private const val SERVICE_START_ACK_TIMEOUT_MS = 5_000L

        private var lastBusyLogAtMs: Long? = null

        @Synchronized
        private fun logBusyRejection() {
            val nowMs = SystemClock.elapsedRealtime()
            val previousMs = lastBusyLogAtMs
            if (previousMs == null || nowMs < previousMs || nowMs - previousMs >= BUSY_LOG_INTERVAL_MS) {
                lastBusyLogAtMs = nowMs
                Log.w(TAG, "Rejecting SSH agent broadcast: bridge admission limit reached")
            }
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != SshAgentContract.ACTION_RUN_ANDROID_SSH_AGENT) {
            return
        }

        // Broadcast sender attribution is only guaranteed while onReceive is
        // active. Capture it before goAsync/return, then resolve the package's
        // framework metadata from these immutable values on the IO coroutine.
        val sender = captureSender()
        val request = try {
            parseRequest(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SSH agent broadcast", e)
            setBroadcastOutcome(SshAgentContract.BroadcastOutcome.INVALID)
            return
        }
        if (request == null) {
            setBroadcastOutcome(SshAgentContract.BroadcastOutcome.INVALID)
            return
        }

        val reservation = when (
            val reserveResult = sshAgentBridgeAdmission.tryReserve(request.sessionId)
        ) {
            is SshAgentBridgeAdmission.ReserveResult.Reserved -> reserveResult.reservation
            SshAgentBridgeAdmission.ReserveResult.Busy,
            SshAgentBridgeAdmission.ReserveResult.Duplicate,
            -> {
                logBusyRejection()
                setBroadcastOutcome(SshAgentContract.BroadcastOutcome.BUSY)
                return
            }
        }
        val ordered = isOrderedBroadcast
        val result = try {
            goAsync()
        } catch (e: Exception) {
            sshAgentBridgeAdmission.reject(
                sessionId = reservation.sessionId,
                outcome = SshAgentContract.BroadcastOutcome.START_FAILED,
            )
            Log.w(TAG, "Failed to begin asynchronous SSH agent broadcast handling", e)
            setBroadcastOutcome(SshAgentContract.BroadcastOutcome.START_FAILED)
            return
        }

        try {
            val di by closestDI { context }
            // Verify that the SSH agent is enabled
            // in the settings.
            val sshAgent by di.instance<GetSshAgent>()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                var outcome = SshAgentContract.BroadcastOutcome.INTERNAL_ERROR
                try {
                    val enabled = sshAgent().first()
                    if (!enabled) {
                        if (!canPostNotifications(context)) {
                            val msg =
                                "Unable to show SSH agent rejection notification because notifications are unavailable"
                            Log.w(TAG, msg)
                        } else try {
                            NotificationManagerCompat.from(context).notify(
                                Notifications.sshAgent.obtainId(),
                                SshAgentNotifications.createServiceRejectedNotification(context),
                            )
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Unable to post SSH agent rejection notification", e)
                        }
                        outcome = SshAgentContract.BroadcastOutcome.DISABLED
                    } else {
                        outcome = handleRequest(
                            context = context,
                            request = request,
                            reservation = reservation,
                            sender = sender,
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to handle SSH agent broadcast", e)
                } finally {
                    try {
                        if (!outcome.accepted) {
                            sshAgentBridgeAdmission.reject(
                                sessionId = reservation.sessionId,
                                outcome = outcome,
                            )
                        }
                        result.setBroadcastOutcome(
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
                sshAgentBridgeAdmission.reject(
                    sessionId = reservation.sessionId,
                    outcome = SshAgentContract.BroadcastOutcome.INTERNAL_ERROR,
                )
                result.setBroadcastOutcome(
                    outcome = SshAgentContract.BroadcastOutcome.INTERNAL_ERROR,
                    ordered = ordered,
                )
            } finally {
                result.finish()
            }
            Log.w(TAG, "Failed to start SSH agent broadcast handling", e)
        }
    }

    private suspend fun handleRequest(
        context: Context,
        request: BroadcastRequest,
        reservation: SshAgentBridgeAdmission.Reservation,
        sender: BroadcastSender?,
    ): SshAgentContract.BroadcastOutcome {
        val senderAppInfo = getSentFromAppInfo(context, sender)
        val senderAppName = senderAppInfo?.appName
        val serviceIntent = SshAgentService.getIntent(
            context = context,
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
            Log.w(TAG, "SSH agent service start rejected, showing a request notification", e)
            if (!canPostNotifications(context)) {
                val msg =
                    "Unable to show SSH agent recovery notification because notifications are unavailable"
                Log.w(TAG, msg)
                return SshAgentContract.BroadcastOutcome.START_FAILED
            }

            return try {
                val notification = SshAgentNotifications.createServiceStartupNotification(
                    context = context,
                    serviceIntent = serviceIntent,
                    appName = senderAppName,
                )
                NotificationManagerCompat.from(context).notify(
                    Notifications.sshAgent.obtainId(),
                    notification,
                )
                if (sshAgentBridgeAdmission.markDeferred(reservation.sessionId)) {
                    SshAgentContract.BroadcastOutcome.DEFERRED
                } else {
                    SshAgentContract.BroadcastOutcome.START_FAILED
                }
            } catch (notificationException: SecurityException) {
                Log.w(TAG, "Unable to post SSH agent recovery notification", notificationException)
                SshAgentContract.BroadcastOutcome.START_FAILED
            }
        } catch (e: Throwable) {
            Log.w(TAG, "SSH agent service start rejected", e)
            return SshAgentContract.BroadcastOutcome.START_FAILED
        }

        return try {
            withTimeout(SERVICE_START_ACK_TIMEOUT_MS) {
                reservation.outcome.await()
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "Timed out waiting for the SSH agent service to start")
            SshAgentContract.BroadcastOutcome.START_FAILED
        }
    }

    private fun setBroadcastOutcome(
        outcome: SshAgentContract.BroadcastOutcome,
    ) {
        if (!isOrderedBroadcast) {
            return
        }
        setResult(
            outcome.resultCode(),
            outcome.wireValue,
            null,
        )
    }

    private fun parseRequest(
        intent: Intent,
    ): BroadcastRequest? {
        val protocolVersion = intent.getIntExtra(
            SshAgentContract.EXTRA_PROTOCOL_VERSION,
            Int.MIN_VALUE,
        )
        if (protocolVersion != SshAgentTcpProtocol.PROTOCOL_VERSION) {
            Log.w(TAG, "Ignoring unsupported SSH agent protocol version=$protocolVersion")
            return null
        }

        val proxyPort = intent.getIntExtra(
            SshAgentContract.EXTRA_PROXY_PORT,
            -1,
        )
        if (proxyPort !in 1..65535) {
            Log.w(TAG, "Ignoring invalid SSH agent proxy port=$proxyPort")
            return null
        }

        val sessionId = intent.getStringExtra(SshAgentContract.EXTRA_SESSION_ID)
        val sessionSecret = intent.getStringExtra(SshAgentContract.EXTRA_SESSION_SECRET)
        if (
            !isValidAndroidSshAgentSessionParameter(
                value = sessionId,
                expectedDecodedSize = SshAgentTcpProtocol.SESSION_ID_LENGTH,
            ) ||
            !isValidAndroidSshAgentSessionParameter(
                value = sessionSecret,
                expectedDecodedSize = SshAgentTcpProtocol.SESSION_SECRET_LENGTH,
            )
        ) {
            Log.w(TAG, "Ignoring invalid SSH agent session parameters")
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
    ): SshAgentMessages.CallerIdentity? {
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

internal fun isValidAndroidSshAgentSessionParameter(
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

private fun SshAgentContract.BroadcastOutcome.resultCode(): Int = if (accepted) {
    Activity.RESULT_OK
} else {
    Activity.RESULT_CANCELED
}

private fun BroadcastReceiver.PendingResult.setBroadcastOutcome(
    outcome: SshAgentContract.BroadcastOutcome,
    ordered: Boolean,
) {
    if (!ordered) {
        return
    }
    setResult(
        outcome.resultCode(),
        outcome.wireValue,
        null,
    )
}
