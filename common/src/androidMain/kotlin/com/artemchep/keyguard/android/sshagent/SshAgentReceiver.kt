package com.artemchep.keyguard.android.sshagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.artemchep.keyguard.android.Notifications
import com.artemchep.keyguard.android.util.canPostNotifications
import com.artemchep.keyguard.common.service.sshagent.SshAgentTcpProtocol
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import com.artemchep.keyguard.common.service.sshagent.buildAndroidSshAgentCallerIdentity
import com.artemchep.keyguard.common.usecase.GetSshAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.kodein.di.android.closestDI
import org.kodein.di.instance
import java.util.Base64
import kotlin.getValue

class SshAgentReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SshAgentReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val result = goAsync()

        val di by closestDI { context }
        // Verify that the SSH agent is enabled
        // in the settings.
        val sshAgent by di.instance<GetSshAgent>()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val enabled = sshAgent().first()
                if (!enabled) {
                    if (!canPostNotifications(context)) {
                        val msg =
                            "Unable to show SSH agent rejection notification because notifications are unavailable"
                        Log.w(TAG, msg)
                        return@launch
                    }

                    try {
                        NotificationManagerCompat.from(context).notify(
                            Notifications.sshAgent.obtainId(),
                            SshAgentNotifications.createServiceRejectedNotification(context),
                        )
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Unable to post SSH agent rejection notification", e)
                    }
                    return@launch
                }

                handleRequest(
                    context = context,
                    intent = intent,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to handle SSH agent broadcast", e)
            } finally {
                result.finish()
            }
        }
    }

    private fun handleRequest(
        context: Context,
        intent: Intent,
    ) {
        val protocolVersion = intent.getIntExtra(
            SshAgentContract.EXTRA_PROTOCOL_VERSION,
            Int.MIN_VALUE,
        )
        val proxyPort = intent.getIntExtra(
            SshAgentContract.EXTRA_PROXY_PORT,
            -1,
        )
        val sessionId = intent.getStringExtra(SshAgentContract.EXTRA_SESSION_ID)
        val sessionSecret = intent.getStringExtra(SshAgentContract.EXTRA_SESSION_SECRET)

        val sessionIdBytes = sessionId
            ?.let(::decodeBase64)
            ?.takeIf { it.size == SshAgentTcpProtocol.SESSION_ID_LENGTH }
        val sessionSecretBytes = sessionSecret
            ?.let(::decodeBase64)
            ?.takeIf { it.size == SshAgentTcpProtocol.SESSION_SECRET_LENGTH }

        if (protocolVersion != SshAgentTcpProtocol.PROTOCOL_VERSION) {
            Log.w(TAG, "Ignoring unsupported SSH agent protocol version=$protocolVersion")
            return
        }

        if (proxyPort !in 1..65535) {
            Log.w(TAG, "Ignoring invalid SSH agent proxy port=$proxyPort")
            return
        }

        if (sessionIdBytes == null || sessionSecretBytes == null) {
            Log.w(TAG, "Ignoring invalid SSH agent session parameters")
            return
        }

        val senderAppInfo = getSentFromAppInfo(context)
        val senderAppName = senderAppInfo?.appName
        val serviceIntent = SshAgentService.getIntent(
            context = context,
            protocolVersion = protocolVersion,
            proxyPort = proxyPort,
            sessionId = sessionId,
            sessionSecret = sessionSecret,
            senderAppName = senderAppName,
            senderAppPackageName = senderAppInfo?.appBundlePath,
        )
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "SSH agent service start rejected, showing a request notification", e)
            if (!canPostNotifications(context)) {
                val msg =
                    "Unable to show SSH agent recovery notification because notifications are unavailable"
                Log.w(TAG, msg)
                return
            }

            try {
                val notification = SshAgentNotifications.createServiceStartupNotification(
                    context = context,
                    serviceIntent = serviceIntent,
                    appName = senderAppName,
                )
                NotificationManagerCompat.from(context).notify(
                    Notifications.sshAgent.obtainId(),
                    notification,
                )
            } catch (notificationException: SecurityException) {
                Log.w(TAG, "Unable to post SSH agent recovery notification", notificationException)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "SSH agent service start rejected", e)
        }
    }

    private fun decodeBase64(
        value: String,
    ): ByteArray? = runCatching {
        Base64.getDecoder().decode(value)
    }.getOrNull()

    private fun getSentFromAppInfo(
        context: Context,
    ): SshAgentMessages.CallerIdentity? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return null
        }

        val packageName = sentFromPackage
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val senderUid = sentFromUid
        val packagesForUid = context.packageManager.getPackagesForUid(senderUid).orEmpty()
        if (packagesForUid.isNotEmpty() && packageName !in packagesForUid) {
            val msg = "Broadcast sender package=$packageName is not owned by uid=$senderUid"
            Log.w(TAG, msg)
            return null
        }

        return buildAndroidSshAgentCallerIdentity(
            appName = resolveAppLabel(context, packageName),
            appBundlePath = packageName,
        )
    }

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
}
