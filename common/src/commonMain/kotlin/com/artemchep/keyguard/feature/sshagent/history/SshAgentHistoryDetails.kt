package com.artemchep.keyguard.feature.sshagent.history

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSshUsageHistory
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
import com.artemchep.keyguard.feature.agent.history.AgentHistoryDetails
import com.artemchep.keyguard.feature.agent.history.AgentHistoryDetailsIcon
import com.artemchep.keyguard.feature.agent.history.AgentHistoryOutcome
import com.artemchep.keyguard.feature.agent.history.agentHistoryCallerSection
import com.artemchep.keyguard.feature.agent.history.agentHistoryFingerprintRow
import com.artemchep.keyguard.feature.agent.history.agentHistoryReferenceRow
import com.artemchep.keyguard.feature.agent.history.agentHistorySectionOrNull
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.serialization.json.Json

internal suspend fun TranslatorScope.sshAgentHistoryDetails(
    id: String,
    event: DSshUsageHistory,
    cipher: DSecret?,
    requestText: String,
    responseText: String,
    formattedDate: String,
    json: Json,
): AgentHistoryDetails {
    val keySection = agentHistorySectionOrNull(
        id = "key",
        title = translate(Res.string.agent_history_details_key),
        rows = listOf(
            agentHistoryReferenceRow(
                cipherId = event.cipherId,
                cipher = cipher,
                label = translate(Res.string.cipher_type_ssh_key),
            ),
            agentHistoryFingerprintRow(
                label = translate(Res.string.fingerprint),
                value = event.fingerprint
                    ?.takeIf { it.isNotBlank() }
                    ?: cipher?.sshKey?.fingerprint,
            ),
        ),
    )
    val callerSection = agentHistoryCallerSection(
        caller = event.caller,
        json = json,
        fallback = translate(Res.string.agent_history_unknown_caller),
    )
    return AgentHistoryDetails(
        id = id,
        icon = when (event.request) {
            SshUsageHistoryRequestType.AGENT_LIST_KEYS -> AgentHistoryDetailsIcon.LIST_KEYS
            SshUsageHistoryRequestType.AGENT_SIGN_DATA -> AgentHistoryDetailsIcon.SSH_SIGN
            SshUsageHistoryRequestType.UNKNOWN -> AgentHistoryDetailsIcon.UNKNOWN
        },
        title = requestText,
        outcome = event.response.outcome(),
        outcomeText = responseText,
        formattedDate = formattedDate,
        sections = listOfNotNull(
            keySection,
            callerSection,
        ),
    )
}

internal fun SshUsageHistoryResponseType.outcome() = when (this) {
    SshUsageHistoryResponseType.SUCCESS -> AgentHistoryOutcome.SUCCESS
    SshUsageHistoryResponseType.USER_DENIED,
    SshUsageHistoryResponseType.KEY_NOT_FOUND,
    SshUsageHistoryResponseType.FAILURE,
    SshUsageHistoryResponseType.VAULT_LOCKED,
    -> AgentHistoryOutcome.FAILURE

    SshUsageHistoryResponseType.UNKNOWN -> AgentHistoryOutcome.NEUTRAL
}
