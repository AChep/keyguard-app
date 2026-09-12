package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.GetBrowserAutofillAgent
import com.artemchep.keyguard.common.usecase.GetBrowserAutofillAgentPairingCode
import com.artemchep.keyguard.common.usecase.GetBrowserAutofillAgentStatus
import com.artemchep.keyguard.common.usecase.PutBrowserAutofillAgent
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.ok
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

fun settingBrowserAgentProvider(
    directDI: DirectDI,
) = settingBrowserAgentProvider(
    getBrowserAutofillAgent = directDI.instance(),
    getBrowserAutofillAgentPairingCode = directDI.instance(),
    getBrowserAutofillAgentStatus = directDI.instance(),
    putBrowserAutofillAgent = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingBrowserAgentProvider(
    getBrowserAutofillAgent: GetBrowserAutofillAgent,
    getBrowserAutofillAgentPairingCode: GetBrowserAutofillAgentPairingCode,
    getBrowserAutofillAgentStatus: GetBrowserAutofillAgentStatus,
    putBrowserAutofillAgent: PutBrowserAutofillAgent,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    getBrowserAutofillAgent(),
    getBrowserAutofillAgentPairingCode(),
    getBrowserAutofillAgentStatus(),
) { enabled, pairingCode, status ->
    if (CurrentPlatform.hasWatch()) {
        return@combine null
    }

    val onCheckedChange = { shouldBrowserAgent: Boolean ->
        putBrowserAutofillAgent(shouldBrowserAgent)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        platformClasses = listOf(
            Platform.Desktop.Linux::class,
            Platform.Desktop.MacOS::class,
            Platform.Desktop.Windows::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "browser",
                "autofill",
                "agent",
                "extension",
            ),
        ),
    ) {
        SettingBrowserAgent(
            checked = enabled,
            status = status,
            pairingCode = pairingCode.takeIf { enabled && it.isNotBlank() },
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingBrowserAgent(
    checked: Boolean,
    status: AgentStatus,
    pairingCode: String?,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.KeyguardWebsite,
        title = {
            Text(
                text = stringResource(Res.string.pref_item_browser_agent_title),
            )
        },
        text = {
            Column {
                val statusText: String
                val statusColor: androidx.compose.ui.graphics.Color
                when (status) {
                    AgentStatus.Unsupported -> {
                        statusText = stringResource(Res.string.pref_item_browser_agent_status_unsupported)
                        statusColor = LocalContentColor.current
                            .combineAlpha(DisabledEmphasisAlpha)
                    }
                    AgentStatus.Starting -> {
                        statusText = stringResource(Res.string.pref_item_browser_agent_status_starting)
                        statusColor = LocalContentColor.current
                            .combineAlpha(DisabledEmphasisAlpha)
                    }
                    AgentStatus.Ready -> {
                        statusText = stringResource(Res.string.pref_item_browser_agent_status_ready)
                        statusColor = MaterialTheme.colorScheme.ok
                    }
                    AgentStatus.Failed -> {
                        statusText = stringResource(Res.string.pref_item_browser_agent_status_failed)
                        statusColor = MaterialTheme.colorScheme.error
                    }
                    AgentStatus.Stopped -> {
                        statusText = stringResource(Res.string.pref_item_browser_agent_status_stopped)
                        statusColor = LocalContentColor.current
                            .combineAlpha(DisabledEmphasisAlpha)
                    }
                }
                Text(
                    color = statusColor,
                    text = statusText,
                )
                if (pairingCode != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            style = MaterialTheme.typography.bodySmall,
                            text = stringResource(Res.string.pref_item_browser_agent_pairing_code, pairingCode),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        val clipboardService by rememberInstance<ClipboardService>()
                        IconButton(
                            modifier = Modifier.size(32.dp),
                            onClick = {
                                clipboardService.setPrimaryClip(
                                    value = pairingCode,
                                    concealed = false,
                                )
                            },
                        ) {
                            Icon(
                                modifier = Modifier.size(16.dp),
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(Res.string.copy),
                            )
                        }
                    }
                }
            }
        },
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
