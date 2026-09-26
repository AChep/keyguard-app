package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistory
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.time.Clock

/**
 * The session identifier stamped on usage-history rows written by the
 * Android IPC provider services.
 */
internal const val ANDROID_IPC_HISTORY_SESSION_ID = "android-openkeychain-ipc"

/**
 * Records one usage-history event: written directly through
 * [recordDirect] when the vault session exposes a [directRecorder],
 * queued as a sealed [PendingUsageHistory] to be flushed on unlock
 * otherwise. Non-fatal failures never propagate to the IPC response.
 */
@Suppress("LongParameterList")
internal suspend fun <Recorder : Any> recordAndroidIpcUsage(
    directRecorder: Recorder?,
    historyQueue: PendingUsageHistoryQueue,
    protocol: PendingUsageHistory.Protocol,
    caller: AndroidIpcCaller,
    json: Json,
    requestType: String,
    responseType: String,
    cipherId: String?,
    fingerprint: String?,
    keygrip: String?,
    recordDirect: suspend (Recorder, PendingUsageHistory) -> Unit,
) {
    val encodedCaller =
        when (protocol) {
            PendingUsageHistory.Protocol.OPENPGP -> caller.encodeGpgUsageHistoryCaller(json)
            PendingUsageHistory.Protocol.SSH -> caller.encodeSshUsageHistoryCaller(json)
        }
    val event =
        PendingUsageHistory(
            id = UUID.randomUUID().toString(),
            protocol = protocol,
            sessionId = ANDROID_IPC_HISTORY_SESSION_ID,
            caller = encodedCaller,
            requestType = requestType,
            responseType = responseType,
            cipherId = cipherId,
            fingerprint = fingerprint,
            keygrip = keygrip,
            timestampEpochMilliseconds =
                Clock.System
                    .now()
                    .toEpochMilliseconds(),
        )
    if (directRecorder != null) {
        val directResult =
            runCatchingNonFatal {
                recordDirect(directRecorder, event)
            }
        if (directResult.isSuccess) {
            return
        }
    }
    runCatchingNonFatal {
        historyQueue.enqueue(event).bind()
    }
}

internal fun AndroidIpcCaller.encodeGpgUsageHistoryCaller(json: Json): String? =
    encodeAndroidIpcUsageHistoryCaller(json) { appName, packageName ->
        GpgAgentMessages.CallerIdentity(
            pid = pid,
            uid = uid,
            appName = appName,
            appBundlePath = packageName,
        )
    }

internal fun AndroidIpcCaller.encodeSshUsageHistoryCaller(json: Json): String? =
    encodeAndroidIpcUsageHistoryCaller(json) { appName, packageName ->
        SshAgentMessages.CallerIdentity(
            pid = pid,
            uid = uid,
            appName = appName,
            appBundlePath = packageName,
        )
    }

private inline fun <reified T> AndroidIpcCaller.encodeAndroidIpcUsageHistoryCaller(
    json: Json,
    create: AndroidIpcCaller.(appName: String, packageName: String) -> T,
): String? {
    val sanitizedPackageName =
        packageName
            .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH)
            ?: return null
    val sanitizedAppName =
        appLabel
            .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
            ?: sanitizedPackageName
    return runCatchingNonFatal {
        json.encodeToString(create(sanitizedAppName, sanitizedPackageName))
    }.getOrNull()
}
