package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.common.usecase.GetGpgAgent
import com.artemchep.keyguard.common.usecase.GetGpgAgentStatus
import com.artemchep.keyguard.common.usecase.PutGpgAgent
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.info
import com.artemchep.keyguard.ui.theme.ok
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingGpgAgentProvider(
    directDI: DirectDI,
) = settingGpgAgentProvider(
    getGpgAgent = directDI.instance(),
    getGpgAgentStatus = directDI.instance(),
    putGpgAgent = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingGpgAgentProvider(
    getGpgAgent: GetGpgAgent,
    getGpgAgentStatus: GetGpgAgentStatus,
    putGpgAgent: PutGpgAgent,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    getGpgAgent(),
    getGpgAgentStatus(),
) { gpgAgent, gpgAgentStatus ->
    val onCheckedChange = { shouldGpgAgent: Boolean ->
        putGpgAgent(shouldGpgAgent)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        platformClasses = listOf(
            Platform.Mobile.Android::class,
            Platform.Desktop.Linux::class,
            Platform.Desktop.MacOS::class,
            Platform.Desktop.Windows::class,
            Platform.Desktop.Other::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "gpg",
                "gnupg",
                "git",
                "agent",
                "sign",
            ),
        ),
    ) {
        SettingGpgAgent(
            checked = gpgAgent,
            status = gpgAgentStatus,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingGpgAgent(
    checked: Boolean,
    status: AgentStatus,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.Key,
        title = {
            Text(
                text = stringResource(Res.string.pref_item_gpg_agent_title),
            )
        },
        text = {
            val statusColor = when (status) {
                AgentStatus.Unsupported,
                AgentStatus.Stopped,
                    -> LocalContentColor.current
                    .combineAlpha(DisabledEmphasisAlpha)

                AgentStatus.Starting -> MaterialTheme.colorScheme.info
                AgentStatus.Ready -> MaterialTheme.colorScheme.ok
                AgentStatus.Failed -> MaterialTheme.colorScheme.error
            }
            val statusText = stringResource(
                when (status) {
                    AgentStatus.Unsupported -> Res.string.pref_item_gpg_agent_status_unsupported
                    AgentStatus.Stopped -> Res.string.pref_item_gpg_agent_status_stopped
                    AgentStatus.Starting -> Res.string.pref_item_gpg_agent_status_starting
                    AgentStatus.Ready -> Res.string.pref_item_gpg_agent_status_ready
                    AgentStatus.Failed -> Res.string.pref_item_gpg_agent_status_failed
                },
            )
            Text(
                color = statusColor,
                text = statusText,
            )
        },
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
