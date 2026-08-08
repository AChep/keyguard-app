package com.artemchep.keyguard.feature.gpgagent.history

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.feature.agent.AgentUsageHistoryCallerInfo
import com.artemchep.keyguard.feature.agent.toAgentUsageHistoryCallerInfo
import kotlinx.serialization.json.Json

internal fun buildGpgUsageHistoryCallerInfo(
    caller: String?,
    json: Json,
): AgentUsageHistoryCallerInfo? {
    caller ?: return null

    return runCatching {
        json.decodeFromString<GpgAgentMessages.CallerIdentity>(caller)
    }
        .getOrNull()
        ?.toAgentUsageHistoryCallerInfo()
}
