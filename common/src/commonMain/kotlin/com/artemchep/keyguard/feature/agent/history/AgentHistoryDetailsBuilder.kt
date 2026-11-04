package com.artemchep.keyguard.feature.agent.history

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_EXECUTABLE_PATH_LENGTH
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.home.vault.screen.toVaultItemPresentation
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.StringResource

/**
 * The caller of a request: every part of its stored identity, or the
 * [fallback] if the log has no identity to show.
 */
internal suspend fun TranslatorScope.agentHistoryCallerSection(
    caller: String?,
    json: Json,
    fallback: String,
    extraRows: List<AgentHistoryDetails.Row> = emptyList(),
): AgentHistoryDetails.Section {
    val identity = caller?.let { value ->
        runCatching {
            json.decodeFromString<CallerIdentity>(value)
        }.getOrNull()
    }
    val identityRows = identity
        ?.let { agentHistoryCallerRows(it) }
        .orEmpty()
        .ifEmpty {
            val row = AgentHistoryDetails.Row.Text(
                id = "caller.unknown",
                icon = AgentHistoryDetailsIcon.APPLICATION,
                label = translate(Res.string.agent_history_details_application),
                value = fallback,
            )
            listOf(row)
        }
    return AgentHistoryDetails.Section(
        id = "caller",
        title = translate(Res.string.agent_history_details_caller),
        rows = identityRows + extraRows,
        table = true,
    )
}

private suspend fun TranslatorScope.agentHistoryCallerRows(
    identity: CallerIdentity,
): List<AgentHistoryDetails.Row> {
    // Android stores the package name of the sender as the app path.
    val android = CurrentPlatform is Platform.Mobile.Android
    return listOfNotNull(
        callerRow(
            id = "caller.app_name",
            icon = AgentHistoryDetailsIcon.APPLICATION,
            label = Res.string.agent_history_details_application,
            value = identity.appName
                .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH),
        ),
        callerRow(
            id = "caller.app_path",
            icon = AgentHistoryDetailsIcon.APPLICATION_PATH,
            label = if (android) Res.string.package_name else Res.string.agent_history_details_application_path,
            value = identity.appBundlePath
                .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH),
            monospace = true,
            copyType = if (android) CopyText.Type.PACKAGE_NAME else CopyText.Type.VALUE,
        ),
        callerRow(
            id = "caller.process_name",
            icon = AgentHistoryDetailsIcon.PROCESS,
            label = Res.string.agent_history_details_process,
            value = identity.processName
                .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH),
        ),
        callerRow(
            id = "caller.executable_path",
            icon = AgentHistoryDetailsIcon.EXECUTABLE,
            label = Res.string.agent_history_details_executable,
            value = identity.executablePath
                .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_EXECUTABLE_PATH_LENGTH),
            monospace = true,
        ),
        callerRow(
            id = "caller.pid",
            icon = AgentHistoryDetailsIcon.PROCESS_ID,
            label = Res.string.agent_history_details_process_id,
            value = identity.pid
                .takeIf { it > 0 }
                ?.toString(),
            monospace = true,
        ),
    )
}

private suspend fun TranslatorScope.callerRow(
    id: String,
    icon: AgentHistoryDetailsIcon,
    label: StringResource,
    value: String?,
    monospace: Boolean = false,
    copyType: CopyText.Type = CopyText.Type.VALUE,
): AgentHistoryDetails.Row.Text? {
    value ?: return null
    return AgentHistoryDetails.Row.Text(
        id = id,
        icon = icon,
        label = translate(label),
        value = value,
        monospace = monospace,
        copyType = copyType,
    )
}

/**
 * The vault item that a request used, or `null` if the
 * log does not know it.
 */
internal suspend fun TranslatorScope.agentHistoryReferenceRow(
    cipherId: String?,
    cipher: DSecret?,
    label: String,
): AgentHistoryDetails.Row.Reference? {
    cipherId ?: return null
    return AgentHistoryDetails.Row.Reference(
        id = "reference",
        label = label,
        cipherId = cipherId,
        accountId = cipher?.accountId,
        item = cipher?.toVaultItemPresentation(
            // Keys and environments have no app or website icons.
            appIcons = false,
            websiteIcons = false,
        ),
    )
}

internal fun agentHistoryFingerprintRow(
    label: String,
    value: String?,
): AgentHistoryDetails.Row.Text? = value
    .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
    ?.let {
        AgentHistoryDetails.Row.Text(
            id = "key.fingerprint",
            icon = AgentHistoryDetailsIcon.FINGERPRINT,
            label = label,
            value = it,
            monospace = true,
            copyType = CopyText.Type.FINGERPRINT,
        )
    }

/** The error code of a failed request. */
internal suspend fun TranslatorScope.agentHistoryErrorSection(
    errorCode: String?,
): AgentHistoryDetails.Section? {
    val value = errorCode
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
        ?: return null
    val row = AgentHistoryDetails.Row.Text(
        id = "error.code",
        icon = AgentHistoryDetailsIcon.ERROR,
        label = translate(Res.string.agent_history_details_error_code),
        value = value,
        monospace = true,
    )
    return AgentHistoryDetails.Section(
        id = "error",
        title = translate(Res.string.agent_history_details_error),
        rows = listOf(row),
    )
}

/** A section of the [rows], or `null` if there are none. */
internal fun agentHistorySectionOrNull(
    id: String,
    title: String?,
    rows: List<AgentHistoryDetails.Row?>,
): AgentHistoryDetails.Section? = rows
    .filterNotNull()
    .takeIf { it.isNotEmpty() }
    ?.let {
        AgentHistoryDetails.Section(
            id = id,
            title = title,
            rows = it,
        )
    }
