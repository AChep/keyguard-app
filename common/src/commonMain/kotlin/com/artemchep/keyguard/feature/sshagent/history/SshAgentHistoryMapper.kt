package com.artemchep.keyguard.feature.sshagent.history

import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.feature.agent.AgentUsageHistoryCallerInfo
import com.artemchep.keyguard.feature.agent.toAgentUsageHistoryCallerInfo
import kotlinx.serialization.json.Json

internal fun buildSshUsageHistoryCallerInfo(
    caller: String?,
    json: Json,
): AgentUsageHistoryCallerInfo? {
    caller ?: return null

    return runCatching {
        json.decodeFromString<CallerIdentity>(caller)
    }
        .getOrNull()
        ?.toAgentUsageHistoryCallerInfo()
}
