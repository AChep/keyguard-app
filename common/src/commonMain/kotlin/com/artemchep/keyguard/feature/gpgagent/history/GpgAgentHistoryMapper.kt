package com.artemchep.keyguard.feature.gpgagent.history

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import kotlinx.serialization.json.Json

internal data class GpgUsageHistoryCallerInfo(
    val primaryLabel: String,
    val secondaryLabel: String?,
)

internal fun buildGpgUsageHistoryCallerInfo(
    caller: String?,
    json: Json,
): GpgUsageHistoryCallerInfo? {
    caller ?: return null

    return runCatching {
        json.decodeFromString<GpgAgentMessages.CallerIdentity>(caller)
    }
        .getOrNull()
        ?.toGpgUsageHistoryCallerInfo()
}

private fun GpgAgentMessages.CallerIdentity.toGpgUsageHistoryCallerInfo(): GpgUsageHistoryCallerInfo? {
    val appName = appName.takeIf { it.isNotBlank() }
    val processName = processName.takeIf { it.isNotBlank() }
    val primaryLabel = appName ?: processName ?: return null
    val secondaryLabel = processName
        ?.takeIf { appName != null && it != appName }

    return GpgUsageHistoryCallerInfo(
        primaryLabel = primaryLabel,
        secondaryLabel = secondaryLabel,
    )
}
