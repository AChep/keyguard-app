package com.artemchep.keyguard.feature.gpgagent.history

import com.artemchep.keyguard.common.model.DGpgUsageHistory
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgUsageHistoryRequestType
import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
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

internal suspend fun TranslatorScope.gpgAgentHistoryDetails(
    id: String,
    event: DGpgUsageHistory,
    cipher: DSecret?,
    requestText: String,
    responseText: String,
    formattedDate: String,
    json: Json,
): AgentHistoryDetails {
    val keygripRow = event.keygrip
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
        ?.let { keygrip ->
            AgentHistoryDetails.Row.Text(
                id = "key.keygrip",
                icon = AgentHistoryDetailsIcon.KEYGRIP,
                label = translate(Res.string.keygrip),
                value = keygrip,
                monospace = true,
            )
        }
    val keySection = agentHistorySectionOrNull(
        id = "key",
        title = translate(Res.string.agent_history_details_key),
        rows = listOf(
            agentHistoryReferenceRow(
                cipherId = event.cipherId,
                cipher = cipher,
                label = translate(Res.string.cipher_type_gpg_key),
            ),
            agentHistoryFingerprintRow(
                label = translate(Res.string.fingerprint),
                value = event.fingerprint
                    ?.takeIf { it.isNotBlank() }
                    ?: cipher?.gpgKey?.fingerprint,
            ),
            keygripRow,
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
            GpgUsageHistoryRequestType.AGENT_LIST_KEYS -> AgentHistoryDetailsIcon.LIST_KEYS
            GpgUsageHistoryRequestType.AGENT_SIGN_HASH -> AgentHistoryDetailsIcon.GPG_SIGN
            GpgUsageHistoryRequestType.AGENT_DECRYPT -> AgentHistoryDetailsIcon.GPG_DECRYPT
            GpgUsageHistoryRequestType.UNKNOWN -> AgentHistoryDetailsIcon.UNKNOWN
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

internal fun GpgUsageHistoryResponseType.outcome() = when (this) {
    GpgUsageHistoryResponseType.SUCCESS -> AgentHistoryOutcome.SUCCESS
    GpgUsageHistoryResponseType.USER_DENIED,
    GpgUsageHistoryResponseType.KEY_NOT_FOUND,
    GpgUsageHistoryResponseType.VAULT_LOCKED,
    GpgUsageHistoryResponseType.UNSUPPORTED,
    GpgUsageHistoryResponseType.FAILURE,
    -> AgentHistoryOutcome.FAILURE

    GpgUsageHistoryResponseType.UNKNOWN -> AgentHistoryOutcome.NEUTRAL
}
