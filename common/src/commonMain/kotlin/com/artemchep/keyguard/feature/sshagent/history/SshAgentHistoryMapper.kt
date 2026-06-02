package com.artemchep.keyguard.feature.sshagent.history

import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import kotlinx.serialization.json.Json

internal data class SshUsageHistoryCallerInfo(
    val primaryLabel: String,
    val secondaryLabel: String?,
)

internal fun buildSshUsageHistoryCallerInfo(
    caller: String?,
    json: Json,
): SshUsageHistoryCallerInfo? {
    caller ?: return null

    return runCatching {
        json.decodeFromString<SshAgentMessages.CallerIdentity>(caller)
    }
        .getOrNull()
        ?.toSshUsageHistoryCallerInfo()
}

private fun SshAgentMessages.CallerIdentity.toSshUsageHistoryCallerInfo(): SshUsageHistoryCallerInfo? {
    val appName = appName.takeIf { it.isNotBlank() }
    val processName = processName.takeIf { it.isNotBlank() }
    val primaryLabel = appName ?: processName ?: return null
    val secondaryLabel = processName
        ?.takeIf { appName != null && it != appName }

    return SshUsageHistoryCallerInfo(
        primaryLabel = primaryLabel,
        secondaryLabel = secondaryLabel,
    )
}
