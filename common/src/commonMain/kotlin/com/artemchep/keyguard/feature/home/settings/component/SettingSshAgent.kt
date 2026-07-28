package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.common.usecase.GetSshAgent
import com.artemchep.keyguard.common.usecase.GetSshAgentStatus
import com.artemchep.keyguard.common.usecase.PutSshAgent
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.icons.KeyguardSshKey
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.info
import com.artemchep.keyguard.ui.theme.ok
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingSshAgentProvider(
    directDI: DirectDI,
) = settingSshAgentProvider(
    getSshAgent = directDI.instance(),
    getSshAgentStatus = directDI.instance(),
    putSshAgent = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingSshAgentProvider(
    getSshAgent: GetSshAgent,
    getSshAgentStatus: GetSshAgentStatus,
    putSshAgent: PutSshAgent,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    getSshAgent(),
    getSshAgentStatus(),
) { sshAgent, sshAgentStatus ->
    // I can not imagine many people running the
    // SSH agent on their watch.
    if (CurrentPlatform.hasWatch()) {
        return@combine null
    }

    val onCheckedChange = { shouldSshAgent: Boolean ->
        putSshAgent(shouldSshAgent)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        platformClasses = listOf(
            Platform.Mobile.Android::class,
            Platform.Desktop.Linux::class,
            Platform.Desktop.MacOS::class,
            Platform.Desktop.Windows::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "ssh",
                "git",
                "agent",
            ),
        ),
    ) {
        SettingSshAgent(
            checked = sshAgent,
            status = sshAgentStatus,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingSshAgent(
    checked: Boolean,
    status: AgentStatus,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.KeyguardSshKey,
        title = {
            Text(
                text = stringResource(Res.string.pref_item_ssh_agent_title),
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
                    AgentStatus.Unsupported -> Res.string.pref_item_ssh_agent_status_unsupported
                    AgentStatus.Stopped -> Res.string.pref_item_ssh_agent_status_stopped
                    AgentStatus.Starting -> Res.string.pref_item_ssh_agent_status_starting
                    AgentStatus.Ready -> Res.string.pref_item_ssh_agent_status_ready
                    AgentStatus.Failed -> Res.string.pref_item_ssh_agent_status_failed
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
