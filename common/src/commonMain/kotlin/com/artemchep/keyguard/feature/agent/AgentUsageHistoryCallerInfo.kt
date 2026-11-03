package com.artemchep.keyguard.feature.agent

import com.artemchep.keyguard.common.service.agent.AgentCallerIdentity
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import kotlinx.serialization.json.Json

internal data class AgentUsageHistoryCallerInfo(
    val primaryLabel: String,
    val secondaryLabel: String?,
)

/** Decodes the JSON [CallerIdentity] that agent history stores; `null` if it is absent or malformed. */
internal fun decodeAgentUsageHistoryCallerInfo(
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

/** Builds the compact application/process identity rendered by agent history. */
internal fun AgentCallerIdentity.toAgentUsageHistoryCallerInfo(): AgentUsageHistoryCallerInfo? {
    val appName = appName.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
    val appBundlePath =
        appBundlePath
            .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH)
    val processName = processName.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
    val primaryLabel = appName ?: appBundlePath ?: processName ?: return null
    val secondaryLabel =
        buildList {
            appBundlePath
                ?.takeIf { it != primaryLabel }
                ?.let(::add)
            processName
                ?.takeIf { it != primaryLabel && it != appBundlePath }
                ?.let(::add)
        }.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = " • ")

    return AgentUsageHistoryCallerInfo(
        primaryLabel = primaryLabel,
        secondaryLabel = secondaryLabel,
    )
}
